package com.shriram.cinecode.data.rea

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class ApkAnalysisReport(
    val fileName: String,
    val fileSizeBytes: Long,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val abis: List<String>,
    val permissions: List<String>,
    val dexCount: Int,
    val nativeLibs: List<String>,
    val assetCount: Int,
    val totalEntries: Int,
    val securityNotes: List<String>
)

/**
 * Streaming REA (Reverse Engineering & APK Analyzer) for CineCode-AI.
 * Uses ZipFile + PackageManager without loading the entire APK into RAM (4GB RAM safe).
 */
object ReaManager {

    suspend fun analyzeUri(context: Context, uri: Uri): Result<ApkAnalysisReport> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "rea_tmp").apply { mkdirs() }
            val tempFile = File(tempDir, "inspect_${System.currentTimeMillis()}.apk")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var len: Int
                        while (input.read(buffer).also { len = it } != -1) {
                            output.write(buffer, 0, len)
                        }
                    }
                } ?: error("Cannot open selected APK stream")
                analyzeFile(context, tempFile)
            } finally {
                tempFile.delete()
            }
        }
    }

    suspend fun analyzeFile(context: Context, apkFile: File): ApkAnalysisReport = withContext(Dispatchers.IO) {
        require(apkFile.exists() && apkFile.isFile) { "APK file does not exist: ${apkFile.absolutePath}" }

        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }

        val packageName = pkgInfo?.packageName ?: "Unknown"
        val versionName = pkgInfo?.versionName ?: "Unknown"
        val versionCode = if (pkgInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            (pkgInfo?.versionCode?.toLong() ?: 0L)
        }
        val permissions = pkgInfo?.requestedPermissions?.toList()?.sorted() ?: emptyList()

        val abis = linkedSetOf<String>()
        val nativeLibs = linkedSetOf<String>()
        var dexCount = 0
        var assetCount = 0
        var totalEntries = 0
        var hasDebuggableHint = false

        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                totalEntries++
                val name = entry.name
                when {
                    name.startsWith("classes") && name.endsWith(".dex") -> dexCount++
                    name.startsWith("assets/") -> assetCount++
                    name.startsWith("lib/") && name.endsWith(".so") -> {
                        val parts = name.split('/')
                        if (parts.size >= 3) {
                            abis.add(parts[1])
                            nativeLibs.add(parts.last())
                        }
                    }
                }
            }
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null && manifestEntry.size in 1..(2 * 1024 * 1024)) {
                val raw = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val ascii = String(raw, Charsets.ISO_8859_1)
                if (ascii.contains("cleartextTraffic", ignoreCase = true)) {
                    hasDebuggableHint = true
                }
            }
        }

        val notes = buildList {
            if ("armeabi-v7a" in abis && "arm64-v8a" in abis) {
                add("Universal ARM support (armeabi-v7a + arm64-v8a)")
            } else if (abis.isEmpty()) {
                add("Pure DEX / Java-Kotlin APK (no native .so binaries)")
            }
            if (dexCount > 6) add("Large multidex APK ($dexCount DEX files)")
            if (permissions.any { it.contains("MANAGE_EXTERNAL_STORAGE") || it.contains("SYSTEM_ALERT_WINDOW") }) {
                add("Uses elevated system permissions")
            }
            if (hasDebuggableHint) add("Manifest references cleartext traffic flag")
            if (apkFile.length() > 150L * 1024 * 1024) {
                add("Large package (>150 MB) — use streaming installs on 64GB devices")
            }
        }

        ApkAnalysisReport(
            fileName = apkFile.name,
            fileSizeBytes = apkFile.length(),
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            abis = abis.toList(),
            permissions = permissions,
            dexCount = dexCount,
            nativeLibs = nativeLibs.take(40),
            assetCount = assetCount,
            totalEntries = totalEntries,
            securityNotes = notes.ifEmpty { listOf("Standard APK layout verified") }
        )
    }
}
