package com.shriram.cinecode.data.updater

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.shriram.cinecode.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

sealed class ApkDownloadState {
    object Idle : ApkDownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusText: String = "डाउनलोडिंग...",
        val speedBytesPerSec: Long = 0L,
        val activeChunks: Int = 1,
        val routeLabel: String = "Direct"
    ) : ApkDownloadState()
    data class Completed(val file: File) : ApkDownloadState()
    data class Error(
        val message: String,
        val resolvedUrl: String? = null
    ) : ApkDownloadState()
}

private data class DownloadProbe(
    val totalBytes: Long,
    val supportsRange: Boolean
)

/**
 * 8-Chunk High-Turbo Multi-Stream APK Downloader for CineCode-AI (4GB RAM / 64GB storage safe):
 * - Probes server for `Accept-Ranges: bytes` (GitHub Releases CDN & Fast Mirrors)
 * - Uses 8 parallel HTTP Range streams with 128 KB buffers (~1 MB active RAM) and per-chunk resume/retry
 * - Supports Fast GitHub Mirror / Proxy routing for slow mobile data networks + direct link copy for 1DM/ADM
 * - Automatically falls back to 128 KB keep-alive resumable single-stream if server doesn't support ranges
 * - Verifies exact Content-Length + ZIP/APK magic header (`PK\x03\x04`) before atomic move & 1-tap install
 */
object AutomaticApkDownloader {
    private const val TAG = "AutomaticApkDownloader"
    private const val REPO_LATEST_RELEASE_API = "https://api.github.com/repos/shriramnag/CineCode-AI/releases/latest"
    private const val REPO_RELEASES_PAGE = "https://github.com/shriramnag/CineCode-AI/releases"
    private const val MAX_ATTEMPTS = 5
    private const val PARALLEL_CHUNKS = 8
    private const val MIN_PARALLEL_BYTES = 4L * 1024L * 1024L
    private const val IO_BUFFER_BYTES = 128 * 1024
    private const val MIN_VALID_APK_BYTES = 1_000_000L
    private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    private const val PROGRESS_EMIT_STEP_BYTES = 256L * 1024L

    private val TURBO_MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://mirror.ghproxy.com/"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val metadataClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val mirrorProbeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val streamClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 16
            maxRequestsPerHost = 10
        })
        .connectionPool(ConnectionPool(10, 45, TimeUnit.SECONDS))
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val state: StateFlow<ApkDownloadState> = _state.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    fun resetState() {
        if (_state.value !is ApkDownloadState.Downloading) {
            _state.value = ApkDownloadState.Idle
        }
    }

    fun formatMb(bytes: Long): String {
        if (bytes <= 0L) return "0.00 MB"
        return String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return ""
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb.coerceAtLeast(1.0))
        }
    }

    fun buildDirectDownloadUrl(versionName: String, preferredDownloadUrl: String): String {
        val candidate = preferredDownloadUrl.trim()
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate
        }
        val cleanVer = versionName.trim().removePrefix("v").removePrefix("V")
        return if (cleanVer.isNotBlank()) {
            "https://github.com/shriramnag/CineCode-AI/releases/download/v$cleanVer/CineCode-AI-v$cleanVer.apk"
        } else {
            REPO_RELEASES_PAGE
        }
    }

    fun copyApkLinkToClipboard(
        context: Context,
        versionName: String,
        preferredDownloadUrl: String
    ): String {
        val appContext = context.applicationContext
        val link = buildDirectDownloadUrl(versionName, preferredDownloadUrl)
        runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("CineCode-AI APK Link", link))
        }
        Toast.makeText(
            appContext,
            "APK लिंक कॉपी हो गया — अब 1DM / ADM में पेस्ट करें",
            Toast.LENGTH_LONG
        ).show()
        return link
    }

    fun startDownload(
        context: Context,
        versionName: String,
        preferredDownloadUrl: String,
        useTurboMirror: Boolean = false
    ) {
        val appContext = context.applicationContext
        cancelDownload(appContext, deletePartial = false)

        _state.value = ApkDownloadState.Downloading(
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = 0L,
            statusText = if (useTurboMirror) {
                "⚡ सबसे तेज़ Turbo Mirror खोजा जा रहा है..."
            } else {
                "⚡ 8x टर्बो डाउनलोड लिंक तैयार हो रहा है..."
            },
            speedBytesPerSec = 0L,
            activeChunks = 1,
            routeLabel = if (useTurboMirror) "Mirror" else "Direct"
        )

        downloadJob = scope.launch {
            var directResolvedUrl = buildDirectDownloadUrl(versionName, preferredDownloadUrl)
            try {
                directResolvedUrl = resolveDownloadUrl(versionName, preferredDownloadUrl)
                val (activeUrl, routeLabel, probe) = selectFastestRoute(directResolvedUrl, useTurboMirror)
                Log.i(TAG, "Starting 8x Turbo APK download [$routeLabel]: $activeUrl (size=${probe.totalBytes}, range=${probe.supportsRange})")

                val cleanVer = versionName.trim()
                    .removePrefix("v")
                    .removePrefix("V")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "latest" }
                val apkFileName = ensureApkExtension("CineCode-AI-v$cleanVer")

                val updatesDir = File(appContext.externalCacheDir ?: appContext.cacheDir, "apk_updates").apply {
                    if (!exists()) mkdirs()
                }
                cleanupStaleApks(updatesDir, apkFileName)

                val partFile = File(updatesDir, "$apkFileName.part")
                val finalFile = File(updatesDir, apkFileName)

                // Reuse already-verified cached APK if size matches remote
                if (finalFile.exists() && finalFile.length() >= MIN_VALID_APK_BYTES && hasValidApkHeader(finalFile)) {
                    if (probe.totalBytes <= 0L || finalFile.length() == probe.totalBytes) {
                        Log.i(TAG, "Reusing verified cached APK: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                        _state.value = ApkDownloadState.Completed(finalFile)
                        withContext(Dispatchers.Main) {
                            triggerApkInstall(appContext, finalFile)
                        }
                        return@launch
                    } else {
                        finalFile.delete()
                    }
                }

                val existingSinglePart = if (partFile.exists()) partFile.length() else 0L
                val downloadedApk = if (probe.supportsRange && probe.totalBytes >= MIN_PARALLEL_BYTES && existingSinglePart == 0L) {
                    try {
                        downloadParallelChunks(
                            downloadUrl = activeUrl,
                            fallbackDirectUrl = directResolvedUrl,
                            routeLabel = routeLabel,
                            totalBytes = probe.totalBytes,
                            apkFileName = apkFileName,
                            partFile = partFile,
                            finalFile = finalFile,
                            updatesDir = updatesDir
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Parallel 8-chunk mode fell back to single-stream: ${e.message}")
                        cleanupChunkFiles(updatesDir, apkFileName)
                        downloadSingleStreamWithResume(
                            downloadUrl = activeUrl,
                            fallbackDirectUrl = directResolvedUrl,
                            routeLabel = routeLabel,
                            initialExpectedBytes = probe.totalBytes,
                            partFile = partFile,
                            finalFile = finalFile,
                            updatesDir = updatesDir
                        )
                    }
                } else {
                    downloadSingleStreamWithResume(
                        downloadUrl = activeUrl,
                        fallbackDirectUrl = directResolvedUrl,
                        routeLabel = routeLabel,
                        initialExpectedBytes = probe.totalBytes,
                        partFile = partFile,
                        finalFile = finalFile,
                        updatesDir = updatesDir
                    )
                }

                _state.value = ApkDownloadState.Completed(downloadedApk)
                withContext(Dispatchers.Main) {
                    val launched = triggerApkInstall(appContext, downloadedApk)
                    if (!launched) {
                        Toast.makeText(
                            appContext,
                            "APK डाउनलोड पूरा हुआ — 'इंस्टॉल करें' दबाएँ",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: CancellationException) {
                _state.value = ApkDownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed: ${e.message}", e)
                val friendlyReason = buildFriendlyError(e)
                _state.value = ApkDownloadState.Error(
                    message = friendlyReason,
                    resolvedUrl = directResolvedUrl.ifBlank { REPO_RELEASES_PAGE }
                )
            }
        }
    }

    /**
     * Resolves the best active route:
     * - If `useTurboMirror` is true and URL is on GitHub, tests fast GitHub mirrors with short timeouts
     *   and picks the first verified healthy mirror; falls back safely to Direct GitHub if mirrors fail.
     * - Otherwise probes Direct GitHub directly.
     */
    private fun selectFastestRoute(
        directUrl: String,
        useTurboMirror: Boolean
    ): Triple<String, String, DownloadProbe> {
        if (useTurboMirror && directUrl.startsWith("https://github.com/", ignoreCase = true)) {
            for (prefix in TURBO_MIRROR_PREFIXES) {
                val mirrorUrl = prefix + directUrl
                val mirrorProbe = probeDownloadCapabilities(mirrorUrl, client = mirrorProbeClient)
                if (mirrorProbe.totalBytes >= MIN_VALID_APK_BYTES) {
                    Log.i(TAG, "Selected fast Turbo Mirror: $prefix (${mirrorProbe.totalBytes} bytes, range=${mirrorProbe.supportsRange})")
                    return Triple(mirrorUrl, "Mirror", mirrorProbe)
                }
            }
            Log.w(TAG, "All Turbo Mirrors unavailable, falling back to Direct GitHub")
        }
        val directProbe = probeDownloadCapabilities(directUrl, client = metadataClient)
        return Triple(directUrl, "Direct", directProbe)
    }

    /**
     * Downloads the APK in 8 lightweight parallel HTTP Range streams (128 KB buffer each = ~1 MB active RAM)
     * and merges them sequentially into the final verified APK.
     */
    private suspend fun downloadParallelChunks(
        downloadUrl: String,
        fallbackDirectUrl: String,
        routeLabel: String,
        totalBytes: Long,
        apkFileName: String,
        partFile: File,
        finalFile: File,
        updatesDir: File
    ): File = coroutineScope {
        val chunkFiles = (0 until PARALLEL_CHUNKS).map { idx ->
            File(updatesDir, "$apkFileName.chunk$idx")
        }

        // Remove stale chunks if a previous run used a different chunk count
        updatesDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("$apkFileName.chunk")) {
                val idx = f.name.substringAfter("$apkFileName.chunk").toIntOrNull()
                if (idx == null || idx !in 0 until PARALLEL_CHUNKS) {
                    f.delete()
                }
            }
        }

        val baseChunkSize = totalBytes / PARALLEL_CHUNKS
        val ranges = (0 until PARALLEL_CHUNKS).map { idx ->
            val start = idx * baseChunkSize
            val end = if (idx == PARALLEL_CHUNKS - 1) totalBytes - 1L else ((idx + 1) * baseChunkSize) - 1L
            start to end
        }

        var initialResumed = 0L
        for (idx in 0 until PARALLEL_CHUNKS) {
            val expectedChunkLen = ranges[idx].second - ranges[idx].first + 1L
            val f = chunkFiles[idx]
            if (f.exists()) {
                val len = f.length()
                if (len > expectedChunkLen) {
                    f.delete()
                } else {
                    initialResumed += len
                }
            }
        }

        val remainingDownload = (totalBytes - initialResumed).coerceAtLeast(0L)
        val requiredFree = remainingDownload + totalBytes + (15L * 1024L * 1024L)
        if (updatesDir.usableSpace in 1L until requiredFree) {
            throw IOException("Parallel merge needs ${formatMb(requiredFree)}, switching to single-stream")
        }

        val totalDownloaded = AtomicLong(initialResumed)
        emitProgress(
            downloadedBytes = initialResumed,
            totalBytes = totalBytes,
            statusText = if (initialResumed > 0L) {
                "⚡ 8x टर्बो रीज़्यूम (${formatMb(initialResumed)} से)..."
            } else if (routeLabel == "Mirror") {
                "⚡ 8x Turbo Mirror हाई-स्पीड डाउनलोड..."
            } else {
                "⚡ 8x टर्बो हाई-स्पीड डाउनलोड..."
            },
            speedBytesPerSec = 0L,
            activeChunks = PARALLEL_CHUNKS,
            routeLabel = routeLabel
        )

        val progressReporter = launch {
            var lastBytes = totalDownloaded.get()
            var lastTime = System.currentTimeMillis()
            var smoothedSpeed = 0L
            while (isActive) {
                delay(PROGRESS_EMIT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val currentBytes = totalDownloaded.get().coerceAtMost(totalBytes)
                val elapsedMs = (now - lastTime).coerceAtLeast(1L)
                val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
                val instantSpeed = (deltaBytes * 1000L) / elapsedMs
                smoothedSpeed = if (smoothedSpeed == 0L) {
                    instantSpeed
                } else {
                    ((smoothedSpeed * 6L) + (instantSpeed * 4L)) / 10L
                }
                lastBytes = currentBytes
                lastTime = now
                emitProgress(
                    downloadedBytes = currentBytes,
                    totalBytes = totalBytes,
                    statusText = if (routeLabel == "Mirror") "⚡ 8x Turbo Mirror डाउनलोडिंग..." else "⚡ 8x टर्बो डाउनलोडिंग...",
                    speedBytesPerSec = smoothedSpeed,
                    activeChunks = PARALLEL_CHUNKS,
                    routeLabel = routeLabel
                )
            }
        }

        try {
            val jobs = (0 until PARALLEL_CHUNKS).map { idx ->
                val (rangeStart, rangeEnd) = ranges[idx]
                async(Dispatchers.IO) {
                    downloadSingleChunkWithRetry(
                        primaryUrl = downloadUrl,
                        fallbackDirectUrl = fallbackDirectUrl,
                        chunkIndex = idx,
                        rangeStart = rangeStart,
                        rangeEnd = rangeEnd,
                        chunkFile = chunkFiles[idx],
                        totalCounter = totalDownloaded
                    )
                }
            }
            jobs.awaitAll()
        } finally {
            progressReporter.cancel()
        }

        for (idx in 0 until PARALLEL_CHUNKS) {
            val expectedLen = ranges[idx].second - ranges[idx].first + 1L
            val actualLen = chunkFiles[idx].length()
            if (actualLen != expectedLen) {
                throw IOException("Chunk #$idx size mismatch ($actualLen / $expectedLen)")
            }
        }

        emitProgress(
            downloadedBytes = totalBytes,
            totalBytes = totalBytes,
            statusText = "फ़ाइल जोड़ी और सत्यापित हो रही है...",
            speedBytesPerSec = 0L,
            activeChunks = PARALLEL_CHUNKS,
            routeLabel = routeLabel
        )

        if (partFile.exists()) partFile.delete()
        FileOutputStream(partFile, false).use { fos ->
            BufferedOutputStream(fos, IO_BUFFER_BYTES).use { out ->
                val buf = ByteArray(IO_BUFFER_BYTES)
                for (chunkFile in chunkFiles) {
                    currentCoroutineContext().ensureActive()
                    chunkFile.inputStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                        }
                    }
                }
                out.flush()
            }
            runCatching { fos.fd.sync() }
        }

        cleanupChunkFiles(updatesDir, apkFileName)

        if (partFile.length() != totalBytes || partFile.length() < MIN_VALID_APK_BYTES || !hasValidApkHeader(partFile)) {
            partFile.delete()
            throw IOException("मर्ज की गई APK फ़ाइल सत्यापन में विफल रही")
        }

        finalizeApkFile(partFile, finalFile, totalBytes)
    }

    private suspend fun downloadSingleChunkWithRetry(
        primaryUrl: String,
        fallbackDirectUrl: String,
        chunkIndex: Int,
        rangeStart: Long,
        rangeEnd: Long,
        chunkFile: File,
        totalCounter: AtomicLong
    ) {
        val expectedChunkLen = rangeEnd - rangeStart + 1L
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            var existingLen = if (chunkFile.exists()) chunkFile.length() else 0L
            if (existingLen == expectedChunkLen) {
                return
            }
            if (existingLen > expectedChunkLen) {
                chunkFile.delete()
                totalCounter.addAndGet(-existingLen)
                existingLen = 0L
            }

            // If mirror fails twice on a chunk, transparently fall back to direct GitHub URL for remaining bytes
            val activeUrl = if (attempt >= 3 && primaryUrl != fallbackDirectUrl) fallbackDirectUrl else primaryUrl
            val reqStart = rangeStart + existingLen
            try {
                val request = Request.Builder()
                    .url(activeUrl)
                    .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/octet-stream, */*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                    .header("Range", "bytes=$reqStart-$rangeEnd")
                    .build()

                streamClient.newCall(request).execute().use { response ->
                    if (response.code != 206) {
                        throw IOException("Chunk #$chunkIndex expected HTTP 206, got HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty body on chunk #$chunkIndex")
                    val append = existingLen > 0L

                    body.byteStream().use { input ->
                        FileOutputStream(chunkFile, append).use { fos ->
                            BufferedOutputStream(fos, IO_BUFFER_BYTES).use { out ->
                                val buffer = ByteArray(IO_BUFFER_BYTES)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    out.write(buffer, 0, read)
                                    totalCounter.addAndGet(read.toLong())
                                }
                                out.flush()
                            }
                            runCatching { fos.fd.sync() }
                        }
                    }
                }

                if (chunkFile.length() == expectedChunkLen) {
                    return
                }
                throw IOException("Chunk #$chunkIndex interrupted (${chunkFile.length()} / $expectedChunkLen)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Chunk #$chunkIndex attempt $attempt/$MAX_ATTEMPTS failed: ${e.message}")
                if (attempt < MAX_ATTEMPTS) {
                    delay((attempt * 1000L).coerceAtMost(4500L))
                }
            }
        }
        throw lastError ?: IOException("Chunk #$chunkIndex failed after $MAX_ATTEMPTS retries")
    }

    private suspend fun downloadSingleStreamWithResume(
        downloadUrl: String,
        fallbackDirectUrl: String,
        routeLabel: String,
        initialExpectedBytes: Long,
        partFile: File,
        finalFile: File,
        updatesDir: File
    ): File {
        var expectedTotalBytes = initialExpectedBytes
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            if (expectedTotalBytes > 0L && existingBytes > expectedTotalBytes) {
                partFile.delete()
                existingBytes = 0L
            }

            val remainingNeeded = if (expectedTotalBytes > 0L) {
                (expectedTotalBytes - existingBytes).coerceAtLeast(0L) + (15L * 1024L * 1024L)
            } else {
                65L * 1024L * 1024L
            }
            if (updatesDir.usableSpace in 1L until remainingNeeded) {
                throw IOException(
                    "फ़ोन में पर्याप्त स्टोरेज खाली नहीं है (कम से कम ${formatMb(remainingNeeded)} खाली जगह चाहिए)।"
                )
            }

            val activeUrl = if (attempt >= 3 && downloadUrl != fallbackDirectUrl) fallbackDirectUrl else downloadUrl
            val currentRouteLabel = if (activeUrl == fallbackDirectUrl) "Direct" else routeLabel

            try {
                val statusPrefix = when {
                    attempt > 1 && existingBytes > 0L ->
                        "रीज़्यूम हो रहा है (${formatMb(existingBytes)} से • प्रयास $attempt/$MAX_ATTEMPTS)..."
                    attempt > 1 ->
                        "पुनः प्रयास ($attempt/$MAX_ATTEMPTS)..."
                    existingBytes > 0L ->
                        "${formatMb(existingBytes)} से आगे डाउनलोड हो रहा है..."
                    else ->
                        "डाउनलोड हो रहा है..."
                }
                emitProgress(existingBytes, expectedTotalBytes, statusPrefix, 0L, 1, currentRouteLabel)

                val reqBuilder = Request.Builder()
                    .url(activeUrl)
                    .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/octet-stream, */*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                streamClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code

                    if (code == 416) {
                        val contentRangeTotal = parseTotalFromContentRange(response.header("Content-Range"))
                        if (contentRangeTotal > 0L) expectedTotalBytes = contentRangeTotal
                        if (expectedTotalBytes > 0L && existingBytes == expectedTotalBytes && hasValidApkHeader(partFile)) {
                            return finalizeApkFile(partFile, finalFile, expectedTotalBytes)
                        }
                        partFile.delete()
                        throw IOException("अधूरी फ़ाइल रीसेट की गई (HTTP 416), पुनः शुरू किया जा रहा है")
                    }

                    if (code != 200 && code != 206) {
                        throw IOException("सर्वर से एरर मिला: HTTP $code (${response.message})")
                    }

                    val body = response.body ?: throw IOException("सर्वर से खाली डेटा मिला")
                    val isPartial = (code == 206 && existingBytes > 0L)

                    val resolvedTotal = if (isPartial) {
                        val fromHeader = parseTotalFromContentRange(response.header("Content-Range"))
                        when {
                            fromHeader > 0L -> fromHeader
                            body.contentLength() > 0L -> existingBytes + body.contentLength()
                            else -> expectedTotalBytes
                        }
                    } else {
                        if (body.contentLength() > 0L) body.contentLength() else expectedTotalBytes
                    }
                    if (resolvedTotal > 0L) {
                        expectedTotalBytes = resolvedTotal
                    }

                    var downloadedBytes = if (isPartial) existingBytes else 0L
                    var lastEmitTime = System.currentTimeMillis()
                    var lastEmitBytes = downloadedBytes
                    var smoothedSpeed = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(partFile, isPartial).use { fos ->
                            BufferedOutputStream(fos, IO_BUFFER_BYTES).use { out ->
                                val buffer = ByteArray(IO_BUFFER_BYTES)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    currentCoroutineContext().ensureActive()
                                    out.write(buffer, 0, read)
                                    downloadedBytes += read

                                    val now = System.currentTimeMillis()
                                    val elapsedMs = now - lastEmitTime
                                    if (elapsedMs >= PROGRESS_EMIT_INTERVAL_MS ||
                                        downloadedBytes - lastEmitBytes >= PROGRESS_EMIT_STEP_BYTES
                                    ) {
                                        val deltaBytes = (downloadedBytes - lastEmitBytes).coerceAtLeast(0L)
                                        val instantSpeed = if (elapsedMs > 0L) (deltaBytes * 1000L) / elapsedMs else smoothedSpeed
                                        smoothedSpeed = if (smoothedSpeed == 0L) {
                                            instantSpeed
                                        } else {
                                            ((smoothedSpeed * 6L) + (instantSpeed * 4L)) / 10L
                                        }
                                        lastEmitTime = now
                                        lastEmitBytes = downloadedBytes
                                        emitProgress(
                                            downloadedBytes,
                                            expectedTotalBytes,
                                            if (attempt > 1) "डाउनलोडिंग (प्रयास $attempt/$MAX_ATTEMPTS)..." else "डाउनलोडिंग...",
                                            smoothedSpeed,
                                            1,
                                            currentRouteLabel
                                        )
                                    }
                                }
                                out.flush()
                            }
                            runCatching { fos.fd.sync() }
                        }
                    }

                    emitProgress(downloadedBytes, expectedTotalBytes, "APK सत्यापित हो रहा है...", 0L, 1, currentRouteLabel)
                }

                val actualLen = partFile.length()
                if (expectedTotalBytes > 0L && actualLen < expectedTotalBytes) {
                    throw IOException(
                        "कनेक्शन बीच में टूट गया (${formatMb(actualLen)} / ${formatMb(expectedTotalBytes)})"
                    )
                }
                if (actualLen < MIN_VALID_APK_BYTES || !hasValidApkHeader(partFile)) {
                    partFile.delete()
                    throw IOException("डाउनलोड की गई APK फ़ाइल अधूरी या अमान्य है, पुनः प्रयास किया जा रहा है")
                }

                return finalizeApkFile(partFile, finalFile, expectedTotalBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                val currentSaved = if (partFile.exists()) partFile.length() else 0L
                Log.w(TAG, "Attempt $attempt/$MAX_ATTEMPTS interrupted at $currentSaved/$expectedTotalBytes bytes: ${e.message}")

                if (attempt < MAX_ATTEMPTS) {
                    val backoffMs = (attempt * 1500L).coerceAtMost(6000L)
                    emitProgress(
                        currentSaved,
                        expectedTotalBytes,
                        "नेटवर्क रुका — ${formatMb(currentSaved)} से रीज़्यूम (${attempt + 1}/$MAX_ATTEMPTS)...",
                        0L,
                        1,
                        currentRouteLabel
                    )
                    delay(backoffMs)
                }
            }
        }

        throw lastError ?: IOException("डाउनलोड कई प्रयासों के बाद पूरा नहीं हो सका")
    }

    private fun finalizeApkFile(partFile: File, finalFile: File, expectedTotalBytes: Long): File {
        if (finalFile.exists()) {
            finalFile.delete()
        }
        val renamed = partFile.renameTo(finalFile)
        if (!renamed) {
            partFile.inputStream().use { input ->
                FileOutputStream(finalFile).use { output ->
                    input.copyTo(output, IO_BUFFER_BYTES)
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }
            partFile.delete()
        }
        if (!finalFile.exists() || finalFile.length() < MIN_VALID_APK_BYTES || !hasValidApkHeader(finalFile)) {
            finalFile.delete()
            throw IOException("फ़ाइनल APK सेव सत्यापन विफल रहा")
        }
        if (expectedTotalBytes > 0L && finalFile.length() != expectedTotalBytes) {
            finalFile.delete()
            throw IOException("फ़ाइनल APK आकार मेल नहीं खाया (${formatMb(finalFile.length())} / ${formatMb(expectedTotalBytes)})")
        }
        return finalFile
    }

    private fun emitProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        statusText: String,
        speedBytesPerSec: Long = 0L,
        activeChunks: Int = 1,
        routeLabel: String = "Direct"
    ) {
        val progress = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        _state.value = ApkDownloadState.Downloading(
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            statusText = statusText,
            speedBytesPerSec = speedBytesPerSec,
            activeChunks = activeChunks,
            routeLabel = routeLabel
        )
    }

    private fun probeDownloadCapabilities(url: String, client: OkHttpClient = metadataClient): DownloadProbe {
        val headProbe = runCatching {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    val acceptRanges = resp.header("Accept-Ranges").orEmpty()
                    DownloadProbe(
                        totalBytes = len,
                        supportsRange = acceptRanges.contains("bytes", ignoreCase = true)
                    )
                } else {
                    DownloadProbe(-1L, false)
                }
            }
        }.getOrDefault(DownloadProbe(-1L, false))

        if (headProbe.totalBytes >= MIN_VALID_APK_BYTES && headProbe.supportsRange) {
            return headProbe
        }

        val rangeProbe = runCatching {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 206) {
                    val total = parseTotalFromContentRange(resp.header("Content-Range"))
                    DownloadProbe(
                        totalBytes = if (total > 0L) total else headProbe.totalBytes,
                        supportsRange = true
                    )
                } else if (resp.isSuccessful) {
                    val len = resp.header("Content-Length")?.toLongOrNull() ?: headProbe.totalBytes
                    DownloadProbe(totalBytes = len, supportsRange = false)
                } else {
                    headProbe
                }
            }
        }.getOrDefault(headProbe)

        return rangeProbe
    }

    private fun parseTotalFromContentRange(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val slash = header.lastIndexOf('/')
        if (slash == -1 || slash + 1 >= header.length) return -1L
        return header.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    private fun hasValidApkHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04
            }
        }.getOrDefault(false)
    }

    private fun cleanupStaleApks(updatesDir: File, keepApkFileName: String) {
        runCatching {
            val keepPartName = "$keepApkFileName.part"
            val keepChunkPrefix = "$keepApkFileName.chunk"
            updatesDir.listFiles()?.forEach { f ->
                if (f.name != keepApkFileName &&
                    f.name != keepPartName &&
                    !f.name.startsWith(keepChunkPrefix)
                ) {
                    f.delete()
                }
            }
        }
    }

    private fun cleanupChunkFiles(updatesDir: File, apkFileName: String) {
        runCatching {
            val prefix = "$apkFileName.chunk"
            updatesDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }

    private fun buildFriendlyError(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("स्टोरेज") || raw.contains("ENOSPC", ignoreCase = true) ->
                "स्टोरेज कम होने के कारण डाउनलोड रुका। कृपया थोड़ी जगह खाली करें या लिंक कॉपी करके डाउनलोड करें।"
            raw.contains("timeout", ignoreCase = true) || raw.contains("SocketTimeout", ignoreCase = true) ->
                "मोबाइल डेटा धीमा होने से टाइमआउट हुआ। '⚡ Turbo Mirror' आज़माएँ या 'पुनः प्रयास (Resume)' दबाएँ।"
            raw.contains("कनेक्शन बीच में टूट गया") || raw.contains("EOF", ignoreCase = true) || raw.contains("reset", ignoreCase = true) ->
                "नेटवर्क ड्रॉप हुआ ($raw)। अधूरी फ़ाइल सुरक्षित है — 'पुनः प्रयास' या '⚡ Turbo Mirror' से आगे रीज़्यूम करें।"
            raw.isNotBlank() ->
                "डाउनलोड रुका: $raw ('⚡ Turbo Mirror' या 1DM/ADM लिंक कॉपी आज़माएँ)"
            else ->
                "नेटवर्क समस्या के कारण डाउनलोड पूरा नहीं हो सका। '⚡ Turbo Mirror' या पुनः प्रयास करें।"
        }
    }

    fun openReleaseFallback(context: Context, versionName: String, preferredDownloadUrl: String) {
        val appContext = context.applicationContext
        val cleanVer = versionName.trim().removePrefix("v").removePrefix("V")
        val targetUrl = buildDirectDownloadUrl(versionName, preferredDownloadUrl)
        val opened = openInBrowser(appContext, targetUrl)
        if (!opened) {
            runCatching {
                val apkName = ensureApkExtension("CineCode-AI-v${cleanVer.ifBlank { "latest" }}")
                enqueueWithDownloadManager(appContext, targetUrl, apkName)
                Toast.makeText(
                    appContext,
                    "Download Manager में $apkName डाउनलोड शुरू हो गया है",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                openInBrowser(appContext, REPO_RELEASES_PAGE)
            }
        }
    }

    fun openInBrowser(context: Context, url: String): Boolean {
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No browser handled update URL: ${e.message}")
            false
        }
    }

    private fun enqueueWithDownloadManager(context: Context, url: String, fileName: String) {
        val safeApkName = ensureApkExtension(fileName)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw IllegalStateException("DownloadManager उपलब्ध नहीं है")
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(safeApkName)
            setDescription("CineCode-AI अपडेट डाउनलोड हो रहा है")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeApkName)
        }
        dm.enqueue(request)
    }

    private fun ensureApkExtension(fileName: String): String {
        val trimmed = fileName.trim().ifBlank { "CineCode-AI-update.apk" }
        return if (trimmed.endsWith(".apk", ignoreCase = true)) trimmed else "$trimmed.apk"
    }

    private fun resolveDownloadUrl(versionName: String, preferredDownloadUrl: String): String {
        val candidate = preferredDownloadUrl.trim()
        val cleanVer = versionName.trim().removePrefix("v").removePrefix("V")
        val taggedUrl = "https://github.com/shriramnag/CineCode-AI/releases/download/v$cleanVer/CineCode-AI-v$cleanVer.apk"

        val apiUrl = runCatching {
            val req = Request.Builder()
                .url(REPO_LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .build()
            metadataClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val assets = json.optJSONArray("assets") ?: return@use null
                var exactMatch: String? = null
                var anyApkMatch: String? = null
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val dlUrl = item.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                        if (name.contains(cleanVer, ignoreCase = true)) {
                            exactMatch = dlUrl
                            break
                        }
                        if (anyApkMatch == null) {
                            anyApkMatch = dlUrl
                        }
                    }
                }
                exactMatch ?: anyApkMatch
            }
        }.getOrNull()

        return when {
            candidate.isNotBlank() && (candidate.contains(cleanVer) || apiUrl.isNullOrBlank()) -> candidate
            !apiUrl.isNullOrBlank() -> apiUrl
            else -> taggedUrl
        }
    }

    fun cancelDownload(context: Context, deletePartial: Boolean = false) {
        runCatching { downloadJob?.cancel() }
        downloadJob = null
        if (deletePartial) {
            runCatching {
                val updatesDir = File(context.applicationContext.externalCacheDir ?: context.applicationContext.cacheDir, "apk_updates")
                updatesDir.listFiles()
                    ?.filter { it.name.endsWith(".part") || it.name.contains(".chunk") }
                    ?.forEach { it.delete() }
            }
        }
        _state.value = ApkDownloadState.Idle
    }

    fun triggerApkInstall(context: Context, apkFile: File): Boolean {
        val appContext = context.applicationContext
        return try {
            if (!apkFile.exists() || apkFile.length() <= 0L) return false

            val readyApkFile = if (apkFile.name.endsWith(".apk", ignoreCase = true)) {
                apkFile
            } else {
                val targetDir = appContext.externalCacheDir ?: appContext.cacheDir
                val normalizedFile = File(targetDir, ensureApkExtension(apkFile.name))
                apkFile.copyTo(normalizedFile, overwrite = true)
                normalizedFile
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()
            ) {
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(manageIntent)
                return false
            }

            val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
            val apkUri = FileProvider.getUriForFile(appContext, authority, readyApkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open APK installer: ${e.message}", e)
            false
        }
    }
}
