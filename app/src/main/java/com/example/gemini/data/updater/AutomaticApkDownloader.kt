package com.shriram.cinecode.data.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.shriram.cinecode.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
    data class Error(val message: String) : ApkDownloadState()
}

/**
 * Autonomous background APK downloader with progress reporting, clean cancellation
 * (cancelling network call, coroutine job, and DownloadManager IDs), and automatic
 * Android PackageInstaller trigger via FileProvider.
 */
object AutomaticApkDownloader {
    private const val TAG = "AutomaticApkDownloader"
    private const val REPO_LATEST_RELEASE_API = "https://api.github.com/repos/shriramnag/CineCode-AI/releases/latest"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val state: StateFlow<ApkDownloadState> = _state.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var activeDownloadManagerId: Long = -1L

    @Volatile
    private var activeTargetFile: File? = null

    fun resetState() {
        if (_state.value !is ApkDownloadState.Downloading) {
            _state.value = ApkDownloadState.Idle
        }
    }

    fun startDownload(
        context: Context,
        versionName: String,
        preferredDownloadUrl: String
    ) {
        val appContext = context.applicationContext
        cancelDownload(appContext)

        _state.value = ApkDownloadState.Downloading(
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = 0L,
            statusText = "डाउनलोडिंग शुरू हो रही है..."
        )

        downloadJob = scope.launch {
            var tempFile: File? = null
            try {
                val resolvedUrl = resolveDownloadUrl(versionName, preferredDownloadUrl)
                Log.i(TAG, "Starting APK download from: $resolvedUrl")

                val targetDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: appContext.externalCacheDir
                    ?: appContext.cacheDir
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val safeVer = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                tempFile = File(targetDir, "CineCode-AI-v${safeVer}-Signed.apk")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                activeTargetFile = tempFile

                val request = Request.Builder()
                    .url(resolvedUrl)
                    .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                    .build()

                val call = httpClient.newCall(request)
                activeCall = call

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("डाउनलोड विफल (HTTP ${response.code})")
                    }
                    val body = response.body ?: throw IllegalStateException("खाली रिस्पॉन्स प्राप्त हुआ")
                    val totalBytes = body.contentLength()

                    body.byteStream().use { input ->
                        tempFile.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var downloadedBytes = 0L
                            var lastEmitTime = 0L

                            while (isActive) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= 120L || (totalBytes > 0 && downloadedBytes >= totalBytes)) {
                                    lastEmitTime = now
                                    val progress = if (totalBytes > 0) {
                                        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    _state.value = ApkDownloadState.Downloading(
                                        progress = progress,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        statusText = if (totalBytes > 0) {
                                            "डाउनलोडिंग... ${(progress * 100).toInt()}%"
                                        } else {
                                            "डाउनलोडिंग..."
                                        }
                                    )
                                }
                            }
                            if (!isActive) {
                                throw CancellationException("Download cancelled by user")
                            }
                            output.flush()
                        }
                    }
                }

                if (!tempFile.exists() || tempFile.length() < 100 * 1024L) {
                    throw IllegalStateException("डाउनलोड की गई APK फ़ाइल अधूरी या अमान्य है")
                }

                verifyApkArchiveForOverwrite(appContext, tempFile)

                activeCall = null
                activeTargetFile = tempFile
                _state.value = ApkDownloadState.Completed(tempFile)

                launch(Dispatchers.Main) {
                    triggerApkInstall(appContext, tempFile)
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "APK download cancelled cleanly")
                runCatching { tempFile?.delete() }
                _state.value = ApkDownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed: ${e.message}", e)
                runCatching { tempFile?.delete() }
                _state.value = ApkDownloadState.Error(e.message ?: "डाउनलोड विफल रहा")
            } finally {
                activeCall = null
            }
        }
    }

    private fun verifyApkArchiveForOverwrite(context: Context, apkFile: File) {
        val pm = context.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: throw IllegalStateException("डाउनलोड की गई APK फ़ाइल करप्ट या अधूरी है (Archive parse failed)")

        if (archiveInfo.packageName != context.packageName) {
            throw IllegalStateException(
                "पैकेज ID मेल नहीं खाता: प्राप्त '${archiveInfo.packageName}', आवश्यक '${context.packageName}'"
            )
        }
        Log.i(TAG, "Verified APK for safe overwrite: pkg=${archiveInfo.packageName}, size=${apkFile.length()}")
    }

    private fun resolveDownloadUrl(versionName: String, preferredDownloadUrl: String): String {
        val candidate = preferredDownloadUrl.trim()
        val taggedUrl = "https://github.com/shriramnag/CineCode-AI/releases/download/v$versionName/CineCode-AI-v$versionName-Signed.apk"

        // Query latest GitHub release asset when possible, falling back cleanly to preferred URL
        val apiUrl = runCatching {
            val req = Request.Builder()
                .url(REPO_LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CineCode-AI-Updater/${BuildConfig.VERSION_NAME}")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                val assets = json.optJSONArray("assets") ?: return@use null
                var signedMatch: String? = null
                var anyApkMatch: String? = null
                for (i in 0 until assets.length()) {
                    val item = assets.optJSONObject(i) ?: continue
                    val name = item.optString("name")
                    val dlUrl = item.optString("browser_download_url")
                    if (name.endsWith("-Signed.apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                        signedMatch = dlUrl
                        if (name.contains(versionName, ignoreCase = true)) break
                    } else if (name.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank() && anyApkMatch == null) {
                        anyApkMatch = dlUrl
                    }
                }
                signedMatch ?: anyApkMatch
            }
        }.getOrNull()

        return when {
            candidate.isNotBlank() && (candidate.contains(versionName) || apiUrl.isNullOrBlank()) -> candidate
            !apiUrl.isNullOrBlank() -> apiUrl
            else -> taggedUrl
        }
    }

    fun cancelDownload(context: Context) {
        val appContext = context.applicationContext
        runCatching { activeCall?.cancel() }
        activeCall = null
        runCatching { downloadJob?.cancel() }
        downloadJob = null

        val dmId = activeDownloadManagerId
        if (dmId != -1L) {
            runCatching {
                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                dm?.remove(dmId)
            }
            activeDownloadManagerId = -1L
        }

        val currentState = _state.value
        if (currentState is ApkDownloadState.Downloading) {
            runCatching { activeTargetFile?.delete() }
        }
        activeTargetFile = null
        _state.value = ApkDownloadState.Idle
    }

    fun triggerApkInstall(context: Context, apkFile: File): Boolean {
        val appContext = context.applicationContext
        return try {
            if (!apkFile.exists() || apkFile.length() < 100 * 1024L) {
                _state.value = ApkDownloadState.Error("APK फ़ाइल मौजूद नहीं है या अधूरी है")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !appContext.packageManager.canRequestPackageInstalls()) {
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(manageIntent)
                return false
            }

            val authority = "${appContext.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(appContext, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }

            val resInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.queryIntentActivities(
                    installIntent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.queryIntentActivities(
                    installIntent,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )
            }
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo?.packageName ?: continue
                runCatching {
                    appContext.grantUriPermission(
                        packageName,
                        apkUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }

            appContext.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Primary ACTION_VIEW installer launch failed, trying fallback: ${e.message}", e)
            return try {
                @Suppress("DEPRECATION")
                val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    val authority = "${appContext.packageName}.fileprovider"
                    data = FileProvider.getUriForFile(appContext, authority, apkFile)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                }
                appContext.startActivity(fallbackIntent)
                true
            } catch (fallbackErr: Exception) {
                Log.e(TAG, "Fallback APK installer also failed: ${fallbackErr.message}", fallbackErr)
                _state.value = ApkDownloadState.Error("इंस्टॉलर खोलने में समस्या: ${fallbackErr.localizedMessage ?: fallbackErr.message}")
                false
            }
        }
    }
}
