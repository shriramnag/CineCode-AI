package com.shriram.cinecode.data.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Simple APK update launcher that resolves the clean GitHub APK URL and opens it
 * via the device browser or system DownloadManager without custom in-app overwrite checks.
 */
object AutomaticApkDownloader {
    private const val TAG = "AutomaticApkDownloader"
    private const val REPO_LATEST_RELEASE_API = "https://api.github.com/repos/shriramnag/CineCode-AI/releases/latest"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
            statusText = "ब्राउज़र / डाउनलोड मैनेजर खुल रहा है..."
        )

        downloadJob = scope.launch {
            try {
                val resolvedUrl = resolveDownloadUrl(versionName, preferredDownloadUrl)
                Log.i(TAG, "Opening APK update URL: $resolvedUrl")

                val cleanVer = versionName.trim()
                    .removePrefix("v")
                    .removePrefix("V")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                val apkFileName = "CineCode-AI-v${cleanVer}.apk"

                withContext(Dispatchers.Main) {
                    val openedInBrowser = openInBrowser(appContext, resolvedUrl)
                    if (!openedInBrowser) {
                        enqueueWithDownloadManager(appContext, resolvedUrl, apkFileName)
                        Toast.makeText(
                            appContext,
                            "Download Manager में $apkFileName डाउनलोड शुरू हो गया है",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    _state.value = ApkDownloadState.Idle
                }
            } catch (e: CancellationException) {
                _state.value = ApkDownloadState.Idle
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open APK update URL: ${e.message}", e)
                _state.value = ApkDownloadState.Error(e.message ?: "अपडेट लिंक खोलने में समस्या")
            }
        }
    }

    private fun openInBrowser(context: Context, url: String): Boolean {
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "No browser handled update URL, falling back to DownloadManager: ${e.message}")
            false
        }
    }

    private fun enqueueWithDownloadManager(context: Context, url: String, fileName: String) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw IllegalStateException("DownloadManager उपलब्ध नहीं है")
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("CineCode-AI अपडेट डाउनलोड हो रहा है")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        dm.enqueue(request)
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
            httpClient.newCall(req).execute().use { resp ->
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

    fun cancelDownload(context: Context) {
        runCatching { downloadJob?.cancel() }
        downloadJob = null
        _state.value = ApkDownloadState.Idle
    }

    fun triggerApkInstall(context: Context, apkFile: File): Boolean {
        val appContext = context.applicationContext
        return try {
            if (!apkFile.exists()) return false
            val authority = "${appContext.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(appContext, authority, apkFile)
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
