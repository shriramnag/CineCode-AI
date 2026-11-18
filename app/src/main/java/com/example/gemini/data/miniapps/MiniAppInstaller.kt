package com.shriram.cinecode.data.miniapps

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Installs CineCode-AI Mini Apps from ZIP archives or scaffolds custom HTML5 Mini Apps directly on-device.
 */
object MiniAppInstaller {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun installFromZipUri(context: Context, uri: Uri): Result<InstalledMiniApp> = withContext(Dispatchers.IO) {
        runCatching {
            val tempDir = File(context.cacheDir, "miniapp_stage_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val canonicalTemp = tempDir.canonicalPath
                val buffer = ByteArray(8192)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val outFile = File(tempDir, entry.name)
                                require(outFile.canonicalPath.startsWith(canonicalTemp)) { "Unsafe entry: ${entry.name}" }
                                outFile.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                    while (true) {
                                        val r = zis.read(buffer)
                                        if (r <= 0) break
                                        bos.write(buffer, 0, r)
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: error("Cannot open ZIP file")

                val manifestFile = tempDir.walkTopDown().firstOrNull { it.name == "manifest.json" }
                    ?: error("manifest.json missing in mini-app archive")
                val sourceRoot = manifestFile.parentFile ?: tempDir
                val manifest = json.decodeFromString<MiniAppManifest>(manifestFile.readText())
                val safeId = manifest.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val destDir = File(MiniAppStore.userRootDir(context), safeId)
                if (destDir.exists()) destDir.deleteRecursively()
                sourceRoot.copyRecursively(destDir, overwrite = true)

                val installed = InstalledMiniApp(
                    manifest = manifest,
                    entryUrl = "file://${File(destDir, manifest.entry).absolutePath}",
                    isBuiltIn = false,
                    installDirPath = destDir.absolutePath
                )
                MiniAppStore.refresh(context)
                installed
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    suspend fun createCustomMiniApp(
        context: Context,
        name: String,
        description: String,
        htmlBody: String
    ): Result<InstalledMiniApp> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanName = name.trim().ifBlank { "Custom Mini App" }
            val safeId = "custom_" + cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "app" }
            val destDir = File(MiniAppStore.userRootDir(context), safeId).apply { mkdirs() }
            val manifest = MiniAppManifest(
                id = safeId,
                name = cleanName,
                description = description.ifBlank { "Custom CineCode-AI Mini App" },
                category = "Custom",
                icon = "✨"
            )
            File(destDir, "manifest.json").writeText(json.encodeToString(manifest))
            File(destDir, "index.html").writeText(htmlBody)
            MiniAppStore.refresh(context)
            InstalledMiniApp(
                manifest = manifest,
                entryUrl = "file://${File(destDir, manifest.entry).absolutePath}",
                isBuiltIn = false,
                installDirPath = destDir.absolutePath
            )
        }
    }

    suspend fun uninstall(context: Context, app: InstalledMiniApp) = withContext(Dispatchers.IO) {
        if (!app.isBuiltIn && !app.installDirPath.isNullOrBlank()) {
            File(app.installDirPath).deleteRecursively()
            MiniAppStore.refresh(context)
        }
    }
}
