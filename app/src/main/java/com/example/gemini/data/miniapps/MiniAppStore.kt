package com.shriram.cinecode.data.miniapps

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Discovers bundled assets/mini-apps/ and user-installed mini-apps under filesDir/cinecode_miniapps/.
 * Lazy-loaded to preserve 4GB RAM.
 */
object MiniAppStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val _apps = MutableStateFlow<List<InstalledMiniApp>>(emptyList())
    val apps: StateFlow<List<InstalledMiniApp>> = _apps.asStateFlow()

    fun userRootDir(context: Context): File =
        File(context.applicationContext.filesDir, "cinecode_miniapps").apply { mkdirs() }

    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<InstalledMiniApp>()

        // 1. Built-in mini-apps from assets/mini-apps/
        val assetFolders = runCatching { context.assets.list("mini-apps")?.toList().orEmpty() }.getOrDefault(emptyList())
        for (folder in assetFolders) {
            val manifestText = runCatching {
                context.assets.open("mini-apps/$folder/manifest.json").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue
            val manifest = runCatching { json.decodeFromString<MiniAppManifest>(manifestText) }.getOrNull() ?: continue
            discovered += InstalledMiniApp(
                manifest = manifest,
                entryUrl = "file:///android_asset/mini-apps/$folder/${manifest.entry}",
                isBuiltIn = true,
                installDirPath = null
            )
        }

        // 2. User-installed mini-apps from filesDir/cinecode_miniapps/
        val userDirs = userRootDir(context).listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in userDirs) {
            val mf = File(dir, "manifest.json")
            if (!mf.exists()) continue
            val manifest = runCatching { json.decodeFromString<MiniAppManifest>(mf.readText()) }.getOrNull() ?: continue
            val entryFile = File(dir, manifest.entry)
            if (!entryFile.exists()) continue
            discovered += InstalledMiniApp(
                manifest = manifest,
                entryUrl = "file://${entryFile.absolutePath}",
                isBuiltIn = false,
                installDirPath = dir.absolutePath
            )
        }

        _apps.value = discovered
    }
}
