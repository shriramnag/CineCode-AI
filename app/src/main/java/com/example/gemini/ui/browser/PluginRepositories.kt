package com.shriram.cinecode.ui.browser

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/** A public GitHub repository holding plugin folders; [ref] is a branch or tag, blank for the default branch. */
data class PluginRepo(val owner: String, val name: String, val ref: String = "") {
    val slug get() = "$owner/$name"
    val label get() = if (ref.isBlank()) slug else "$slug@$ref"
}

/** One plugin a repository offers; [version] comes from the index, or from its manifest when the index has none. */
data class RepoPlugin(val path: String, val id: String, val name: String, val version: String, val description: String, val iconUrl: String)

data class RepoListing(val title: String, val plugins: List<RepoPlugin>, val fromIndex: Boolean)

/** A running repository install: phase is listing | downloading | extracting | installing, then starting (loading it in the app). */
data class RepoInstallProgress(val phase: String, val bytesDone: Long, val bytesTotal: Long, val filesDone: Int, val filesTotal: Int, val note: String = "") {
    /** 0..1 when the size is known, else null (the bar then just moves). */
    val fraction: Float? get() = if (bytesTotal > 0 && phase in setOf("downloading", "extracting")) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null
}

/** Dotted numeric versions, as shown and compared in the plugin screens. */
object PluginVersions {
    fun label(version: String) = if (version.isBlank()) "no version" else "v" + version.removePrefix("v")

    /** Part-by-part comparison; null when either version is missing or not numeric. */
    fun compare(a: String, b: String): Int? {
        if (a.isBlank() || b.isBlank()) return null
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: return null }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: return null }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 }); if (d != 0) return d }
        return 0
    }
}

/**
 * The user's plugin repositories and what each offers. Listing (from a repo's `antigem-plugins.json` or
 * `cinecode-plugins.json`, or its top-level folders with a manifest.json) and installing a single folder
 * are done by the IDE bridge or via GitHub API fallback.
 */
object PluginRepositories {
    const val INDEX = "antigem-plugins.json"
    const val CINECODE_INDEX = "cinecode-plugins.json"
    private const val PREFS = "plugin_repositories"
    const val CINECODE_REPO = "shriramnag/CineCode-AI"
    const val DEFAULT_REPO = CINECODE_REPO
    private const val LEGACY_DEFAULT_REPO = "dipeshkrydv/antigem_plugins"
    private const val CINECODE_CREATOR_PLUGIN_ID = "cinecode-creator-studio"
    private const val DEFAULT_REPO_ADDED = "default_repo_added"
    private const val CINECODE_REPO_ADDED = "cinecode_repo_added"
    private val NAME = "[A-Za-z0-9_.-]+"

    private val githubHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val repos = mutableStateListOf<PluginRepo>()
    /** Latest listing and error per [PluginRepo.label]; [loading] holds labels being refreshed. */
    val listings = mutableStateMapOf<String, RepoListing>()
    val errors = mutableStateMapOf<String, String>()
    val loading = mutableStateListOf<String>()
    /** The repository install or update in progress, polled from the IDE bridge; null when none is running. */
    var installProgress by mutableStateOf<RepoInstallProgress?>(null)
        private set

    var isCloudRepoFallbackActive by mutableStateOf(false)
        private set

    var syncOfflineRepos by mutableStateOf(true)

    private var appContext: Context? = null

    private fun builtInCreatorRepoPlugin() = RepoPlugin(
        path = "extensions/cinecode-creator-studio",
        id = CINECODE_CREATOR_PLUGIN_ID,
        name = "🎬 CineCode 2050 Creator Studio",
        version = "1.1.0",
        description = "3D, 2D रियलिस्टिक, भारतीय पौराणिक (Mythology), In-App Code Sandbox, Shiv AI Emotion Voice और Google Research स्किल्स का आपका अपना विशेष प्लगइन।",
        iconUrl = ""
    )

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        syncOfflineRepos = prefs.getBoolean("sync_offline_repos", true)
        prefs.getString("repos", null)?.let { stored ->
            runCatching { val array = JSONArray(stored); repos.addAll((0 until array.length()).map { parse(array.getString(it)) }) }
        }
        var changed = repos.removeAll { it.slug.equals(LEGACY_DEFAULT_REPO, ignoreCase = true) }
        val cinecode = parse(CINECODE_REPO)
        if (!prefs.getBoolean(CINECODE_REPO_ADDED, false) || repos.isEmpty()) {
            if (repos.none { it.slug.equals(cinecode.slug, ignoreCase = true) }) {
                repos.add(0, cinecode)
                changed = true
            }
            prefs.edit()
                .putBoolean(DEFAULT_REPO_ADDED, true)
                .putBoolean(CINECODE_REPO_ADDED, true)
                .apply()
        }
        if (changed) persist()
        if (repos.any { it.slug.equals(CINECODE_REPO, ignoreCase = true) } && !listings.containsKey(cinecode.label)) {
            listings[cinecode.label] = RepoListing(
                title = "${cinecode.label} (Creator Studio)",
                plugins = listOf(builtInCreatorRepoPlugin()),
                fromIndex = false
            )
        }
    }

    fun updateSyncOfflineRepos(enabled: Boolean) {
        syncOfflineRepos = enabled
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("sync_offline_repos", enabled)?.apply()
    }

    /** Accepts `owner/repo`, `owner/repo@branch`, `https://github.com/owner/repo` and `…/tree/branch` URLs. */
    fun parse(input: String): PluginRepo {
        val text = input.trim().removeSuffix("/").removeSuffix(".git").replace(Regex("^(https?://)?(www\\.)?github\\.com/"), "")
        val tree = Regex("^($NAME)/($NAME)/tree/(.+)$").find(text)
        val plain = Regex("^($NAME)/($NAME)(?:@(.+))?$").find(text)
        val match = tree ?: plain ?: throw IllegalArgumentException("Use owner/repo, owner/repo@branch or a github.com repository URL")
        val ref = match.groupValues[3]
        require(ref.none { it.isWhitespace() } && ref.split('/').none { it.isEmpty() || it == ".." } || ref.isEmpty()) { "Invalid branch or tag: $ref" }
        return PluginRepo(match.groupValues[1], match.groupValues[2], ref)
    }

    fun add(repo: PluginRepo) {
        require(repos.none { it.label.equals(repo.label, ignoreCase = true) }) { "${repo.label} is already added" }
        repos.add(repo); persist()
    }

    fun remove(repo: PluginRepo) {
        repos.remove(repo); listings.remove(repo.label); errors.remove(repo.label); persist()
    }

    private fun persist() {
        val context = appContext ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("repos", JSONArray(repos.map { it.label }).toString()).apply()
    }

    suspend fun refreshAll() = coroutineScope { repos.toList().map { async { refresh(it) } }.awaitAll() }

    suspend fun refresh(repo: PluginRepo) {
        if (repo.label in loading) return
        withContext(Dispatchers.Main) { loading.add(repo.label) }
        val bridgeResult = runCatching { PluginBridge.repoList(repo.label) }
        val finalResult = if (bridgeResult.isSuccess) {
            withContext(Dispatchers.Main) { isCloudRepoFallbackActive = false }
            bridgeResult
        } else if (syncOfflineRepos) {
            val fallback = runCatching { fetchListingFromGitHub(repo) }
            if (fallback.isSuccess) {
                withContext(Dispatchers.Main) { isCloudRepoFallbackActive = true }
            }
            fallback
        } else {
            bridgeResult
        }
        withContext(Dispatchers.Main) {
            finalResult.onSuccess { listings[repo.label] = it; errors.remove(repo.label) }
                .onFailure { errors[repo.label] = it.message ?: "Could not read ${repo.label}" }
            loading.remove(repo.label)
        }
    }

    private suspend fun fetchListingFromGitHub(repo: PluginRepo): RepoListing = withContext(Dispatchers.IO) {
        val branch = repo.ref.ifBlank { "main" }
        val refQuery = if (repo.ref.isNotBlank()) "?ref=${repo.ref}" else ""
        val isCineCodeRepo = repo.slug.equals(CINECODE_REPO, ignoreCase = true)

        // 1. Try direct antigem-plugins.json or cinecode-plugins.json from raw.githubusercontent.com
        for (indexFile in listOf(INDEX, CINECODE_INDEX)) {
            val rawIndexUrl = "https://raw.githubusercontent.com/${repo.slug}/$branch/$indexFile"
            val rawText = httpGetText(rawIndexUrl)
            if (rawText != null && rawText.trimStart().startsWith("{")) {
                val json = org.json.JSONObject(rawText)
                val pluginsArr = json.optJSONArray("plugins") ?: JSONArray()
                val list = (0 until pluginsArr.length()).mapNotNull { i ->
                    val p = pluginsArr.optJSONObject(i) ?: return@mapNotNull null
                    RepoPlugin(
                        path = p.optString("path"),
                        id = p.optString("id"),
                        name = p.optString("name", p.optString("path")),
                        version = p.optString("version", "1.0.0"),
                        description = p.optString("description"),
                        iconUrl = p.optString("iconUrl")
                    )
                }.toMutableList()
                if (isCineCodeRepo && list.none { it.id == CINECODE_CREATOR_PLUGIN_ID }) {
                    list.add(0, builtInCreatorRepoPlugin())
                }
                return@withContext RepoListing(
                    title = json.optString("title").ifBlank { repo.label },
                    plugins = list,
                    fromIndex = true
                )
            }
        }

        // 2. Fallback: scan api.github.com/repos/{slug}/contents (and extensions/)
        val discovered = mutableListOf<RepoPlugin>()
        for (sub in listOf("", "extensions")) {
            val apiPath = if (sub.isEmpty()) "" else "/$sub"
            val apiUrl = "https://api.github.com/repos/${repo.slug}/contents$apiPath$refQuery"
            val contentsRaw = httpGetText(apiUrl) ?: continue
            if (!contentsRaw.trimStart().startsWith("[")) continue
            val entries = JSONArray(contentsRaw)
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                if (item.optString("type") != "dir") continue
                val folderPath = item.optString("path")
                val manifestRaw = httpGetText("https://raw.githubusercontent.com/${repo.slug}/$branch/$folderPath/manifest.json")
                if (manifestRaw != null && manifestRaw.trimStart().startsWith("{")) {
                    val m = org.json.JSONObject(manifestRaw)
                    discovered += RepoPlugin(
                        path = folderPath,
                        id = m.optString("id", item.optString("name")),
                        name = m.optString("name", item.optString("name")),
                        version = m.optString("version", "1.0.0"),
                        description = m.optString("description", "Synced via GitHub API fallback"),
                        iconUrl = ""
                    )
                }
            }
        }
        if (isCineCodeRepo && discovered.none { it.id == CINECODE_CREATOR_PLUGIN_ID }) {
            discovered.add(0, builtInCreatorRepoPlugin())
            return@withContext RepoListing(
                title = "${repo.label} (Creator Studio)",
                plugins = discovered,
                fromIndex = false
            )
        }
        RepoListing(title = "${repo.label} (Cloud GitHub)", plugins = discovered, fromIndex = false)
    }

    private fun httpGetText(url: String): String? = runCatching {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "CineCode-AI-Android")
            .build()
        githubHttp.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()

    /** The installed plugin a repository entry corresponds to: same repo and folder, else the same plugin ID. */
    fun installedFor(repo: PluginRepo, plugin: RepoPlugin): BrowserExtension? {
        val installed = BrowserExtensionManager.instance.extensions
        return installed.firstOrNull { it.source?.repo.equals(repo.slug, ignoreCase = true) && it.source?.path == plugin.path }
            ?: plugin.id.takeIf { it.isNotBlank() }?.let { id -> installed.firstOrNull { it.id == id } }
    }

    /** A newer version of [extension] in the repository it was installed from, if one has been seen. */
    fun updateFor(extension: BrowserExtension): RepoPlugin? {
        val source = extension.source ?: return null
        val repo = repos.firstOrNull { it.slug.equals(source.repo, ignoreCase = true) && it.ref == source.ref } ?: return null
        val offered = listings[repo.label]?.plugins?.firstOrNull { it.path == source.path } ?: return null
        return offered.takeIf { (PluginVersions.compare(it.version, extension.version) ?: 0) > 0 }
    }

    suspend fun install(repo: PluginRepo, plugin: RepoPlugin, folder: String, updateId: String?, asNew: Boolean = false): BrowserExtension = coroutineScope {
        withContext(Dispatchers.Main) { installProgress = RepoInstallProgress("listing", 0, 0, 0, 0) }
        val poller = launch(Dispatchers.IO) {
            while (true) {
                val p = runCatching { PluginBridge.repoProgress(folder.trim()) }.getOrNull()
                // The bridge is done once it stops reporting; the app then loads the new files
                withContext(Dispatchers.Main) { installProgress = p ?: installProgress?.copy(phase = "starting") }
                delay(250)
            }
        }
        try {
            val bridgeAttempt = runCatching {
                BrowserExtensionManager.instance.installFromRepo(repo.label, plugin.path, folder, updateId, asNew)
            }
            if (bridgeAttempt.isSuccess) {
                bridgeAttempt.getOrThrow()
            } else {
                installViaGitHubFallback(repo, plugin, folder)
            }
        } finally {
            poller.cancel()
            withContext(Dispatchers.Main + NonCancellable) { installProgress = null }
        }
    }

    private suspend fun installViaGitHubFallback(repo: PluginRepo, plugin: RepoPlugin, folder: String): BrowserExtension = withContext(Dispatchers.IO) {
        val ctx = appContext ?: error("PluginRepositories not initialized")
        if (plugin.id == CINECODE_CREATOR_PLUGIN_ID) {
            BrowserExtensionManager.instance.loadExtensions()
            AutonomousSkillLoader.scanLocalAndAssets(ctx)
            BrowserExtensionManager.instance.extensions.firstOrNull { it.id == CINECODE_CREATOR_PLUGIN_ID }
                ?.let { return@withContext it }
        }
        val targetDir = java.io.File(ctx.filesDir, "extensions/${folder.trim()}").apply { mkdirs() }
        withContext(Dispatchers.Main) {
            installProgress = RepoInstallProgress("downloading", 0, 0, 0, 1, "Syncing from GitHub API...")
        }
        downloadGitHubDirectory(repo, plugin.path, targetDir)
        java.io.File(targetDir, PluginSource.FILE).writeText(
            PluginSource(repo.slug, repo.ref, plugin.path).toJson().toString()
        )
        BrowserExtensionManager.instance.loadExtensions()
        AutonomousSkillLoader.scanLocalAndAssets(ctx)
        BrowserExtensionManager.instance.extensions.firstOrNull { it.folderName == folder.trim() }
            ?: BrowserExtension(
                id = plugin.id.ifBlank { folder.trim() },
                name = plugin.name,
                version = plugin.version,
                description = plugin.description,
                folderName = folder.trim(),
                dirPath = targetDir.absolutePath,
                source = PluginSource(repo.slug, repo.ref, plugin.path)
            )
    }

    private fun downloadGitHubDirectory(repo: PluginRepo, remotePath: String, destDir: java.io.File) {
        val refQuery = if (repo.ref.isNotBlank()) "?ref=${repo.ref}" else ""
        val apiUrl = "https://api.github.com/repos/${repo.slug}/contents/$remotePath$refQuery"
        val raw = httpGetText(apiUrl) ?: return
        if (!raw.trimStart().startsWith("[")) return
        val entries = JSONArray(raw)
        destDir.mkdirs()
        for (i in 0 until entries.length()) {
            val item = entries.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val type = item.optString("type")
            if (type == "file") {
                val dl = item.optString("download_url")
                if (dl.isNotBlank()) {
                    httpGetText(dl)?.let { content -> java.io.File(destDir, name).writeText(content) }
                }
            } else if (type == "dir") {
                downloadGitHubDirectory(repo, item.optString("path"), java.io.File(destDir, name))
            }
        }
    }
}
