package com.shriram.cinecode.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.core.content.FileProvider
import com.shriram.cinecode.data.remote.HubMediaResolver
import com.shriram.cinecode.domain.model.ArtifactSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object ArtifactDownloadHelper {

    fun downloadArtifact(
        context: Context,
        artifact: ArtifactSnapshot,
        coroutineScope: CoroutineScope,
        snackbarHostState: SnackbarHostState? = null
    ) {
        val targetUri = artifact.absoluteUri.ifBlank { artifact.name }
        if (targetUri.isBlank()) {
            Toast.makeText(context, "No artifact file to download", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            val (savedUri, mimeType, fileName) = withContext(Dispatchers.IO) {
                saveArtifactToDownloads(context, artifact)
            }

            if (savedUri != null) {
                if (snackbarHostState != null) {
                    val result = snackbarHostState.showSnackbar(
                        message = "$fileName saved to Downloads/CineCode-AI",
                        actionLabel = "Open",
                        duration = SnackbarDuration.Short
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        openFile(context, savedUri, mimeType)
                    }
                } else {
                    Toast.makeText(context, "$fileName saved to Downloads/CineCode-AI", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Failed to download $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFile(context: Context, uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType.ifBlank { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open File"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open file", Toast.LENGTH_SHORT).show()
        }
    }

    private val streamingClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private suspend fun saveArtifactToDownloads(
        context: Context,
        artifact: ArtifactSnapshot
    ): Triple<Uri?, String, String> = withContext(Dispatchers.IO) {
        var stagedTempFile: File? = null
        try {
            val rawSource = artifact.absoluteUri.ifBlank { artifact.name }

            // Stream remote HTTP/HTTPS files to a temporary .part file with auto-resume (safe for 4GB RAM)
            val isRemoteHttp = rawSource.startsWith("http://") || rawSource.startsWith("https://")
            val bytes: ByteArray? = if (isRemoteHttp) {
                stagedTempFile = downloadHttpToTempFile(context, rawSource)
                if (stagedTempFile == null) return@withContext Triple(null, "*/*", artifact.name)
                null
            } else {
                resolveFileBytes(context, rawSource) ?: return@withContext Triple(null, "*/*", artifact.name)
            }

            // Determine file name
            val uriFileName = rawSource.removePrefix("file://").substringBefore('?').substringAfterLast("/")
            var fileName = if (artifact.name.isNotBlank()) {
                val clean = artifact.name.substringAfterLast("/")
                if (clean.contains(".")) clean else if (uriFileName.contains(".")) "$clean.${uriFileName.substringAfterLast(".")}" else clean
            } else {
                uriFileName
            }

            // Determine mime type and extension
            var extension = if (fileName.contains(".")) fileName.substringAfterLast(".").lowercase() else ""
            if (extension.isBlank()) {
                val headBytes = bytes ?: readHeadBytes(stagedTempFile)
                if (isImageBytes(headBytes)) {
                    extension = "png"
                    fileName = "$fileName.png"
                } else {
                    extension = "md"
                    fileName = "$fileName.md"
                }
            }

            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: when (extension) {
                    "apk" -> "application/vnd.android.package-archive"
                    "md", "markdown" -> "text/markdown"
                    "py" -> "text/x-python"
                    "kt" -> "text/x-kotlin"
                    "json" -> "application/json"
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    "txt" -> "text/plain"
                    else -> "*/*"
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CineCode-AI")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, contentValues) ?: return@withContext Triple(null, mimeType, fileName)

                resolver.openOutputStream(itemUri)?.use { out ->
                    if (stagedTempFile != null) {
                        stagedTempFile.inputStream().use { input ->
                            input.copyTo(out, 64 * 1024)
                        }
                    } else if (bytes != null) {
                        out.write(bytes)
                    }
                    out.flush()
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                Triple(itemUri, mimeType, fileName)
            } else {
                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CineCode-AI")
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                val partTarget = File(downloadsDir, "$fileName.part")
                if (stagedTempFile != null) {
                    stagedTempFile.inputStream().use { input ->
                        partTarget.outputStream().use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.flush()
                        }
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (!partTarget.renameTo(targetFile)) {
                        partTarget.copyTo(targetFile, overwrite = true)
                        partTarget.delete()
                    }
                } else if (bytes != null) {
                    targetFile.writeBytes(bytes)
                }

                val fileUri = try {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
                } catch (_: Exception) {
                    Uri.fromFile(targetFile)
                }
                Triple(fileUri, mimeType, fileName)
            }
        } catch (e: Exception) {
            Triple(null, "*/*", artifact.name)
        } finally {
            runCatching { stagedTempFile?.delete() }
        }
    }

    private suspend fun downloadHttpToTempFile(context: Context, source: String): File? = withContext(Dispatchers.IO) {
        val fixedSource = com.shriram.cinecode.data.preferences.AuthPreferences.rebaseBridgeUrl(source)
        val isBridge = com.shriram.cinecode.data.preferences.AuthPreferences.isBridgeUrl(fixedSource)
        val client = if (isBridge) {
            com.shriram.cinecode.data.remote.AgyBridgeService.instance.client
        } else {
            streamingClient
        }
        val cacheDir = File(context.externalCacheDir ?: context.cacheDir, "artifact_downloads").apply {
            if (!exists()) mkdirs()
        }
        val stamp = System.currentTimeMillis()
        val partFile = File(cacheDir, "dl_$stamp.part")

        // For non-bridge large remote assets (>= 4 MB), attempt 4-chunk parallel Range download
        if (!isBridge) {
            val probedTotal = runCatching {
                val probeReq = Request.Builder()
                    .url(fixedSource)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                client.newCall(probeReq).execute().use { resp ->
                    if (resp.code == 206) {
                        resp.header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
                    } else -1L
                }
            }.getOrDefault(-1L)

            if (probedTotal >= 4L * 1024L * 1024L) {
                val chunkFiles = (0 until 4).map { File(cacheDir, "dl_${stamp}_c$it.part") }
                val parallelOk = runCatching {
                    coroutineScope {
                        val chunkSize = probedTotal / 4L
                        val jobs = (0 until 4).map { idx ->
                            val start = idx * chunkSize
                            val end = if (idx == 3) probedTotal - 1L else ((idx + 1) * chunkSize) - 1L
                            val expectedLen = end - start + 1L
                            async(Dispatchers.IO) {
                                var lastErr: Exception? = null
                                for (attempt in 1..3) {
                                    val existing = if (chunkFiles[idx].exists()) chunkFiles[idx].length() else 0L
                                    if (existing == expectedLen) return@async
                                    try {
                                        val req = Request.Builder()
                                            .url(fixedSource)
                                            .header("Accept-Encoding", "identity")
                                            .header("Connection", "keep-alive")
                                            .header("Range", "bytes=${start + existing}-$end")
                                            .get()
                                            .build()
                                        client.newCall(req).execute().use { resp ->
                                            if (resp.code != 206) throw java.io.IOException("Chunk $idx HTTP ${resp.code}")
                                            val body = resp.body ?: throw java.io.IOException("Empty chunk body")
                                            body.byteStream().use { input ->
                                                java.io.FileOutputStream(chunkFiles[idx], existing > 0L).use { fos ->
                                                    java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->
                                                        val buf = ByteArray(64 * 1024)
                                                        var read: Int
                                                        while (input.read(buf).also { read = it } != -1) {
                                                            out.write(buf, 0, read)
                                                        }
                                                        out.flush()
                                                    }
                                                }
                                            }
                                        }
                                        if (chunkFiles[idx].length() == expectedLen) return@async
                                        throw java.io.IOException("Chunk $idx incomplete")
                                    } catch (e: Exception) {
                                        lastErr = e
                                        if (attempt < 3) kotlinx.coroutines.delay(attempt * 1200L)
                                    }
                                }
                                throw lastErr ?: java.io.IOException("Chunk $idx failed")
                            }
                        }
                        jobs.forEach { it.await() }
                    }
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
                            }
                            out.flush()
                        }
                    }
                    partFile.length() == probedTotal
                }.getOrDefault(false)

                chunkFiles.forEach { runCatching { it.delete() } }
                if (parallelOk) {
                    return@withContext partFile
                }
                runCatching { partFile.delete() }
            }
        }

        var expectedTotal = -1L
        for (attempt in 1..3) {
            val existing = if (partFile.exists()) partFile.length() else 0L
            try {
                val reqBuilder = Request.Builder()
                    .url(fixedSource)
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")
                    .get()
                if (existing > 0L) {
                    reqBuilder.header("Range", "bytes=$existing-")
                }
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 416) {
                        partFile.delete()
                        throw java.io.IOException("Range reset (HTTP 416)")
                    }
                    if (resp.code != 200 && resp.code != 206) {
                        throw java.io.IOException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw java.io.IOException("Empty response body")
                    val isPartial = resp.code == 206 && existing > 0L
                    val fromRange = resp.header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
                    val total = when {
                        isPartial && fromRange > 0L -> fromRange
                        isPartial && body.contentLength() > 0L -> existing + body.contentLength()
                        body.contentLength() > 0L -> body.contentLength()
                        else -> expectedTotal
                    }
                    if (total > 0L) expectedTotal = total

                    body.byteStream().use { input ->
                        java.io.FileOutputStream(partFile, isPartial).use { fos ->
                            java.io.BufferedOutputStream(fos, 64 * 1024).use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                                out.flush()
                            }
                        }
                    }
                }
                if (expectedTotal > 0L && partFile.length() < expectedTotal) {
                    throw java.io.IOException("Incomplete stream (${partFile.length()} / $expectedTotal)")
                }
                return@withContext partFile
            } catch (_: Exception) {
                if (attempt < 3) {
                    kotlinx.coroutines.delay(attempt * 1500L)
                }
            }
        }
        runCatching { partFile.delete() }
        null
    }

    private fun readHeadBytes(file: File?): ByteArray {
        if (file == null || !file.exists()) return ByteArray(0)
        return runCatching {
            val buf = ByteArray(16)
            val count = file.inputStream().use { it.read(buf) }
            if (count > 0) buf.copyOf(count) else ByteArray(0)
        }.getOrDefault(ByteArray(0))
    }

    private suspend fun resolveFileBytes(context: Context, source: String): ByteArray? {
        // 1. In-memory media cache
        val memKey = HubMediaResolver.normalizeKey(source)
        HubMediaResolver.getImageBytes(memKey)?.let { return it }

        // 2. Base64 data URL
        if (source.startsWith("data:")) {
            val b64 = source.substringAfter("base64,")
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        }

        // 3. Local filesystem
        val clean = source.removePrefix("file://")
        val localFile = File(clean)
        if (localFile.exists() && localFile.canRead() && localFile.length() > 0) {
            return try {
                localFile.readBytes()
            } catch (_: Exception) {
                null
            }
        }

        // 4. Hub media resolver
        val resolved = HubMediaResolver.resolveMediaUri(context, source)
        if (resolved.startsWith("data:")) {
            val b64 = resolved.substringAfter("base64,")
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
        } else if (resolved.startsWith("file://")) {
            val f = File(resolved.removePrefix("file://"))
            if (f.exists() && f.canRead()) {
                return try {
                    f.readBytes()
                } catch (_: Exception) {
                    null
                }
            }
        }

        return null
    }

    private fun isImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG magic: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // JPEG magic: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // GIF magic: 47 49 46 38
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
        // WEBP: RIFF....WEBP
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) return true
        return false
    }
}
