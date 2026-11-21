package com.shriram.cinecode.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.shriram.cinecode.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdateManager"
private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/shriramnag/CineCode-AI/main/version.json"

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val packageName: String,
    val downloadUrl: String,
    val changelog: List<String>,
    val isCritical: Boolean = false
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()


    /**
     * Checks if a new version is available by fetching centralized version.json from GitHub.
     */
    suspend fun checkForUpdates(
        versionJsonUrl: String = DEFAULT_VERSION_URL,
        forceCheck: Boolean = false
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(versionJsonUrl)
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            var response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code == 404) {
                val alternateUrl = when {
                    versionJsonUrl.contains("/main/") -> versionJsonUrl.replace("/main/", "/master/")
                    versionJsonUrl.contains("/master/") -> versionJsonUrl.replace("/master/", "/main/")
                    else -> null
                }
                if (alternateUrl != null) {
                    val fallbackRequest = request.newBuilder()
                        .url(alternateUrl)
                        .build()
                    val fallbackResponse = try {
                        httpClient.newCall(fallbackRequest).execute()
                    } catch (_: Exception) {
                        null
                    }
                    if (fallbackResponse != null && fallbackResponse.isSuccessful) {
                        response.close()
                        response = fallbackResponse
                    } else {
                        fallbackResponse?.close()
                    }
                }
            }
            val rawJson = response.use { resp ->
                if (!resp.isSuccessful) {
                    val errorMsg = when (resp.code) {
                        404 -> "version.json not found on GitHub repository (HTTP 404). Please ensure version.json is pushed to the repository."
                        403 -> "GitHub rate limit exceeded or access forbidden (HTTP 403)."
                        else -> "Failed to fetch version info: HTTP ${resp.code} (${resp.message})"
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }
                resp.body?.string() ?: return@withContext Result.failure(Exception("Empty version response from server"))
            }

            val json = JSONObject(rawJson)
            val remoteVersionCode = json.optInt("version_code", 0)
            val remoteVersionName = json.optString("version_name", "1.0.0")
            val minSupportedCode = json.optInt("min_supported_version_code", 0)
            val changelog = when (val raw = json.opt("changelog")) {
                is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is String -> listOf(raw)
                else -> listOf("General improvements and bug fixes.")
            }
            val isCritical = json.optBoolean("is_critical", false) || (BuildConfig.VERSION_CODE < minSupportedCode)

            // Auto-generate package-specific download URL based on remote version name and flavor
            val currentPkg = context.packageName
            val cleanVer = remoteVersionName.trim().removePrefix("v").removePrefix("V")
            val downloadUrl = json.optString("url").takeIf { it.isNotBlank() }
                ?: "https://github.com/shriramnag/CineCode-AI/releases/download/v$cleanVer/CineCode-AI-v$cleanVer.apk"

            if (remoteVersionCode > BuildConfig.VERSION_CODE && downloadUrl.isNotBlank()) {
                // Verify that the APK asset is actually published and downloadable on GitHub
                val probeRequest = Request.Builder()
                    .url(downloadUrl)
                    .head()
                    .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                    .build()

                val isAvailable = try {
                    httpClient.newCall(probeRequest).execute().use { probeResponse ->
                        probeResponse.isSuccessful || probeResponse.isRedirect
                    }
                } catch (_: Exception) {
                    false
                }

                if (!isAvailable) {
                    // Release is not yet fully published or APK is still building on CI
                    return@withContext Result.success(null)
                }

                Result.success(
                    AppUpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = remoteVersionName,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        packageName = currentPkg,
                        downloadUrl = downloadUrl,
                        changelog = changelog,
                        isCritical = isCritical
                    )
                )
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads the APK file to cache directory using 4 parallel HTTP Range chunks when supported
     * (e.g. GitHub Releases CDN), with automatic fallback to 64 KB buffered keep-alive single-stream
     * resume and retry.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.externalCacheDir ?: context.cacheDir, "apk_updates").apply {
            if (!exists()) mkdirs()
        }
        val safeName = downloadUrl.substringAfterLast('/').substringBefore('?')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "CineCode-AI-update.apk" }
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val partFile = File(updatesDir, "$safeName.part")
        val finalFile = File(updatesDir, safeName)

        // Probe server Range support + Content-Length
        val (probedTotal, supportsRange) = probeRangeSupport(downloadUrl)
        val existingPartLen = if (partFile.exists()) partFile.length() else 0L

        if (supportsRange && probedTotal >= 4L * 1024L * 1024L && existingPartLen == 0L) {
            try {
                val merged = downloadParallelFourChunks(
                    downloadUrl = downloadUrl,
                    totalBytes = probedTotal,
                    updatesDir = updatesDir,
                    safeName = safeName,
                    partFile = partFile,
                    finalFile = finalFile,
                    onProgress = onProgress
                )
                return@withContext Result.success(merged)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Parallel 4-chunk downloadApk falling back to single-stream: ${e.message}")
                updatesDir.listFiles()?.filter { it.name.startsWith("$safeName.chunk") }?.forEach { it.delete() }
            }
        }

        var expectedTotalBytes = probedTotal
        var lastError: Exception? = null
        val maxAttempts = 5

        for (attempt in 1..maxAttempts) {
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            if (expectedTotalBytes > 0L && existingBytes > expectedTotalBytes) {
                partFile.delete()
                existingBytes = 0L
            }

            try {
                val reqBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                    .header("Accept", "application/octet-stream, */*")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                }

                downloadHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    if (response.code == 416) {
                        partFile.delete()
                        throw java.io.IOException("Range reset (HTTP 416)")
                    }
                    if (response.code != 200 && response.code != 206) {
                        throw java.io.IOException("Download failed: HTTP ${response.code}")
                    }
                    val body = response.body ?: throw java.io.IOException("Empty download body")
                    val isPartial = response.code == 206 && existingBytes > 0L
                    val totalFromRange = response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.trim()
                        ?.toLongOrNull() ?: -1L
                    val totalBytes = when {
                        isPartial && totalFromRange > 0L -> totalFromRange
                        isPartial && body.contentLength() > 0L -> existingBytes + body.contentLength()
                        body.contentLength() > 0L -> body.contentLength()
                        else -> expectedTotalBytes
                    }
                    if (totalBytes > 0L) expectedTotalBytes = totalBytes

                    var downloadedBytes = if (isPartial) existingBytes else 0L
                    var lastEmitTime = 0L

                    body.byteStream().use { input ->
                        java.io.FileOutputStream(partFile, isPartial).use { fos ->
                            java.io.BufferedOutputStream(fos, 64 * 1024).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= 250L) {
                                        lastEmitTime = now
                                        val progress = if (expectedTotalBytes > 0L) {
                                            (downloadedBytes.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0f, 1f)
                                        } else 0f
                                        withContext(Dispatchers.Main) {
                                            onProgress(progress, downloadedBytes, expectedTotalBytes)
                                        }
                                    }
                                }
                                output.flush()
                            }
                            runCatching { fos.fd.sync() }
                        }
                    }

                    val finalProgress = if (expectedTotalBytes > 0L) {
                        (downloadedBytes.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0f, 1f)
                    } else 1f
                    withContext(Dispatchers.Main) {
                        onProgress(finalProgress, downloadedBytes, expectedTotalBytes)
                    }
                }

                if (expectedTotalBytes > 0L && partFile.length() < expectedTotalBytes) {
                    throw java.io.IOException("Incomplete APK download (${partFile.length()} / $expectedTotalBytes bytes)")
                }
                if (partFile.length() < 1_000_000L) {
                    partFile.delete()
                    throw java.io.IOException("Downloaded APK file is too small or invalid")
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                return@withContext Result.success(finalFile)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "downloadApk attempt $attempt/$maxAttempts failed: ${e.message}")
                if (attempt < maxAttempts) {
                    kotlinx.coroutines.delay((attempt * 1800L).coerceAtMost(8000L))
                }
            }
        }
        Result.failure(lastError ?: Exception("Download APK failed after retries"))
    }

    private fun probeRangeSupport(downloadUrl: String): Pair<Long, Boolean> {
        return runCatching {
            val req = Request.Builder()
                .url(downloadUrl)
                .get()
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=0-0")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 206) {
                    val total = resp.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.trim()
                        ?.toLongOrNull() ?: -1L
                    Pair(total, total > 0L)
                } else if (resp.isSuccessful) {
                    Pair(resp.header("Content-Length")?.toLongOrNull() ?: -1L, false)
                } else {
                    Pair(-1L, false)
                }
            }
        }.getOrDefault(Pair(-1L, false))
    }

    private suspend fun downloadParallelFourChunks(
        downloadUrl: String,
        totalBytes: Long,
        updatesDir: File,
        safeName: String,
        partFile: File,
        finalFile: File,
        onProgress: (Float, Long, Long) -> Unit
    ): File = coroutineScope {
        val chunkCount = 4
        val chunkSize = totalBytes / chunkCount
        val chunkFiles = (0 until chunkCount).map { File(updatesDir, "$safeName.chunk$it") }
        val ranges = (0 until chunkCount).map { idx ->
            val start = idx * chunkSize
            val end = if (idx == chunkCount - 1) totalBytes - 1L else ((idx + 1) * chunkSize) - 1L
            start to end
        }
        val downloadedCounter = java.util.concurrent.atomic.AtomicLong(0L)
        chunkFiles.forEachIndexed { idx, file ->
            val expected = ranges[idx].second - ranges[idx].first + 1L
            if (file.exists()) {
                if (file.length() > expected) file.delete()
                else downloadedCounter.addAndGet(file.length())
            }
        }

        val reporter = launch {
            while (true) {
                kotlinx.coroutines.delay(250L)
                val current = downloadedCounter.get().coerceAtMost(totalBytes)
                val progress = (current.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                withContext(Dispatchers.Main) {
                    onProgress(progress, current, totalBytes)
                }
            }
        }

        try {
            val jobs = (0 until chunkCount).map { idx ->
                val (start, end) = ranges[idx]
                async(Dispatchers.IO) {
                    val expectedLen = end - start + 1L
                    var lastErr: Exception? = null
                    for (attempt in 1..4) {
                        var existing = if (chunkFiles[idx].exists()) chunkFiles[idx].length() else 0L
                        if (existing == expectedLen) return@async
                        if (existing > expectedLen) {
                            chunkFiles[idx].delete()
                            downloadedCounter.addAndGet(-existing)
                            existing = 0L
                        }
                        try {
                            val req = Request.Builder()
                                .url(downloadUrl)
                                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                                .header("Accept-Encoding", "identity")
                                .header("Connection", "keep-alive")
                                .header("Range", "bytes=${start + existing}-$end")
                                .build()
                            downloadHttpClient.newCall(req).execute().use { resp ->
                                if (resp.code != 206) throw java.io.IOException("Chunk $idx HTTP ${resp.code}")
                                val body = resp.body ?: throw java.io.IOException("Empty chunk body")
                                body.byteStream().use { input ->
                                    java.io.FileOutputStream(chunkFiles[idx], existing > 0L).use { fos ->
                                        java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var read: Int
                                            while (input.read(buf).also { read = it } != -1) {
                                                out.write(buf, 0, read)
                                                downloadedCounter.addAndGet(read.toLong())
                                            }
                                            out.flush()
                                        }
                                    }
                                }
                            }
                            if (chunkFiles[idx].length() == expectedLen) return@async
                            throw java.io.IOException("Chunk $idx incomplete")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastErr = e
                            if (attempt < 4) kotlinx.coroutines.delay(attempt * 1200L)
                        }
                    }
                    throw lastErr ?: java.io.IOException("Chunk $idx failed")
                }
            }
            jobs.forEach { it.await() }
        } finally {
            reporter.cancel()
        }

        if (partFile.exists()) partFile.delete()
        java.io.FileOutputStream(partFile, false).use { fos ->
            java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                for (cf in chunkFiles) {
                    cf.inputStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                        }
                    }
                    cf.delete()
                }
                out.flush()
            }
            runCatching { fos.fd.sync() }
        }

        if (partFile.length() != totalBytes || partFile.length() < 1_000_000L) {
            partFile.delete()
            throw java.io.IOException("Merged APK size mismatch")
        }
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        withContext(Dispatchers.Main) {
            onProgress(1f, totalBytes, totalBytes)
        }
        finalFile
    }

    /**
     * Launches the system Package Installer using FileProvider.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 8.0+ Unknown sources permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return false
                }
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }
}
