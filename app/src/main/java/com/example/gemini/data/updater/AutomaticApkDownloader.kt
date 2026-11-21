package com.shriram.cinecode.data.updater

import android.app.DownloadManager
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

sealed class ApkDownloadState {
    object Idle : ApkDownloadState()
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val statusText: String = "डाउनलोडिंग..."
    ) : ApkDownloadState()
    data class Completed(val file: File) : ApkDownloadState()
    data class Error(
        val message: String,
        val resolvedUrl: String? = null
    ) : ApkDownloadState()
}

/**
 * Resilient in-app APK downloader engineered for large (~46 MB+) GitHub Release APKs on 4GB RAM devices:
 * - Long read/connect timeouts with unlimited total call timeout
 * - 64 KB buffered streaming directly to a deterministic `.part` temporary file
 * - Automatic HTTP Range resume & exponential backoff retries (up to 5 attempts) when streams drop near 90%
 * - Full Content-Length + APK/ZIP magic header (`PK\x03\x04`) verification before atomic `.part` -> `.apk` move
 * - Clean browser & GitHub Release fallback if the user prefers external download
 */
object AutomaticApkDownloader {
    private const val TAG = "AutomaticApkDownloader"
    private const val REPO_LATEST_RELEASE_API = "https://api.github.com/repos/shriramnag/CineCode-AI/releases/latest"
    private const val REPO_RELEASES_PAGE = "https://github.com/shriramnag/CineCode-AI/releases"
    private const val MAX_ATTEMPTS = 5
    private const val IO_BUFFER_BYTES = 64 * 1024
    private const val MIN_VALID_APK_BYTES = 1_000_000L
    private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    private const val PROGRESS_EMIT_STEP_BYTES = 256L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val metadataClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    fun startDownload(
        context: Context,
        versionName: String,
        preferredDownloadUrl: String
    ) {
        val appContext = context.applicationContext
        cancelDownload(appContext, deletePartial = false)

        _state.value = ApkDownloadState.Downloading(
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = 0L,
            statusText = "डाउनलोड लिंक तैयार हो रहा है..."
        )

        downloadJob = scope.launch {
            var resolvedUrl = preferredDownloadUrl.trim()
            try {
                resolvedUrl = resolveDownloadUrl(versionName, preferredDownloadUrl)
                Log.i(TAG, "Starting resumable APK download: $resolvedUrl")

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

                // If final APK already downloaded cleanly earlier, reuse and install immediately
                if (finalFile.exists() && finalFile.length() >= MIN_VALID_APK_BYTES && hasValidApkHeader(finalFile)) {
                    val remoteSize = probeRemoteContentLength(resolvedUrl)
                    if (remoteSize <= 0L || finalFile.length() == remoteSize) {
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

                val downloadedApk = downloadWithResumeAndRetry(
                    downloadUrl = resolvedUrl,
                    partFile = partFile,
                    finalFile = finalFile,
                    updatesDir = updatesDir
                )

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
                Log.e(TAG, "Resumable APK download failed: ${e.message}", e)
                val friendlyReason = buildFriendlyError(e)
                _state.value = ApkDownloadState.Error(
                    message = friendlyReason,
                    resolvedUrl = resolvedUrl.ifBlank { REPO_RELEASES_PAGE }
                )
            }
        }
    }

    private suspend fun downloadWithResumeAndRetry(
        downloadUrl: String,
        partFile: File,
        finalFile: File,
        updatesDir: File
    ): File {
        var expectedTotalBytes = probeRemoteContentLength(downloadUrl)
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            var existingBytes = if (partFile.exists()) partFile.length() else 0L

            // Check if partial file is already oversized/corrupt vs expected size
            if (expectedTotalBytes > 0L && existingBytes > expectedTotalBytes) {
                partFile.delete()
                existingBytes = 0L
            }

            // Storage safety check (important on 64GB devices)
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
                emitProgress(existingBytes, expectedTotalBytes, statusPrefix)

                val reqBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/octet-stream, */*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                streamClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code

                    // HTTP 416: Range not satisfiable (either already complete or stale partial file)
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
                    var lastEmitTime = 0L
                    var lastEmitBytes = downloadedBytes

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
                                    if (now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS ||
                                        downloadedBytes - lastEmitBytes >= PROGRESS_EMIT_STEP_BYTES
                                    ) {
                                        lastEmitTime = now
                                        lastEmitBytes = downloadedBytes
                                        emitProgress(
                                            downloadedBytes,
                                            expectedTotalBytes,
                                            if (attempt > 1) "डाउनलोडिंग (प्रयास $attempt/$MAX_ATTEMPTS)..." else "डाउनलोडिंग..."
                                        )
                                    }
                                }
                                out.flush()
                            }
                            runCatching { fos.fd.sync() }
                        }
                    }

                    emitProgress(downloadedBytes, expectedTotalBytes, "APK सत्यापित हो रहा है...")
                }

                // Verify completeness before accepting the download
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
                    val backoffMs = (attempt * 1800L).coerceAtMost(8000L)
                    emitProgress(
                        currentSaved,
                        expectedTotalBytes,
                        "नेटवर्क रुका — ${formatMb(currentSaved)} से रीज़्यूम (${attempt + 1}/$MAX_ATTEMPTS)..."
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

    private fun emitProgress(downloadedBytes: Long, totalBytes: Long, statusText: String) {
        val progress = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        _state.value = ApkDownloadState.Downloading(
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            statusText = statusText
        )
    }

    private fun probeRemoteContentLength(url: String): Long {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Accept-Encoding", "identity")
                .build()
            metadataClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                } else {
                    -1L
                }
            }
        }.getOrDefault(-1L)
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
                // Standard ZIP / APK local file header: PK\x03\x04
                b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04
            }
        }.getOrDefault(false)
    }

    private fun cleanupStaleApks(updatesDir: File, keepApkFileName: String) {
        runCatching {
            val keepPartName = "$keepApkFileName.part"
            updatesDir.listFiles()?.forEach { f ->
                if (f.name != keepApkFileName && f.name != keepPartName) {
                    f.delete()
                }
            }
        }
    }

    private fun buildFriendlyError(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("स्टोरेज") || raw.contains("ENOSPC", ignoreCase = true) ->
                "स्टोरेज कम होने के कारण डाउनलोड रुका। कृपया थोड़ी जगह खाली करें या ब्राउज़र से डाउनलोड करें।"
            raw.contains("timeout", ignoreCase = true) || raw.contains("SocketTimeout", ignoreCase = true) ->
                "नेटवर्क धीमा होने से कनेक्शन टाइमआउट हुआ। 'पुनः प्रयास' दबाएँ — डाउनलोड वहीं से आगे बढ़ेगा (या ब्राउज़र से डाउनलोड करें)।"
            raw.contains("कनेक्शन बीच में टूट गया") || raw.contains("EOF", ignoreCase = true) || raw.contains("reset", ignoreCase = true) ->
                "नेटवर्क ड्रॉप हुआ ($raw)। अधूरी फ़ाइल सुरक्षित है — 'पुनः प्रयास' दबाने पर वहीं से रीज़्यूम होगा।"
            raw.isNotBlank() ->
                "डाउनलोड रुका: $raw (पुनः प्रयास करें या ब्राउज़र से डाउनलोड करें)"
            else ->
                "नेटवर्क समस्या के कारण डाउनलोड पूरा नहीं हो सका। पुनः प्रयास करें या ब्राउज़र से डाउनलोड करें।"
        }
    }

    fun openReleaseFallback(context: Context, versionName: String, preferredDownloadUrl: String) {
        val appContext = context.applicationContext
        val cleanVer = versionName.trim().removePrefix("v").removePrefix("V")
        val targetUrl = preferredDownloadUrl.trim().ifBlank {
            if (cleanVer.isNotBlank()) {
                "https://github.com/shriramnag/CineCode-AI/releases/download/v$cleanVer/CineCode-AI-v$cleanVer.apk"
            } else {
                REPO_RELEASES_PAGE
            }
        }
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
                updatesDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
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
