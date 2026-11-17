package com.shriram.cinecode.ui.browser

import android.content.Context
import android.util.Log
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import com.shriram.cinecode.data.remote.CustomSlashCommand
import com.shriram.cinecode.data.remote.CustomSlashCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class LoadedSkillItem(
    val id: String,
    val title: String,
    val description: String,
    val source: String,
    val filePath: String
)

/**
 * AutonomousSkillLoader: Automatically scans local skills folders (markdown skill files, auto-skills.md,
 * plugin skills, and JSON MCP configs) and syncs GitHub skills directly via GitHub Contents API
 * without requiring local bridge :1234 or Node.js.
 */
object AutonomousSkillLoader {
    private const val TAG = "AutonomousSkillLoader"
    const val DEFAULT_GITHUB_REPO = "shriramnag/CineCode-AI"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val _loadedSkills = MutableStateFlow<List<LoadedSkillItem>>(emptyList())
    val loadedSkills: StateFlow<List<LoadedSkillItem>> = _loadedSkills.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isActiveConnected = MutableStateFlow(true)
    val isActiveConnected: StateFlow<Boolean> = _isActiveConnected.asStateFlow()

    private val _statusMessage = MutableStateFlow("✅ कौशल फ़ाइलें सिंक / Active & Connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    suspend fun scanLocalAndAssets(context: Context): List<LoadedSkillItem> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val collected = LinkedHashMap<String, LoadedSkillItem>()

        // 1. Ensure default skills directory, bundled assets/skills/*.md, and starter auto-skills.md exist
        val localSkillsDir = File(appCtx.filesDir, "skills").apply { mkdirs() }
        runCatching {
            appCtx.assets.list("skills").orEmpty()
                .filter { it.endsWith(".md", ignoreCase = true) }
                .forEach { assetName ->
                    val dest = File(localSkillsDir, assetName)
                    appCtx.assets.open("skills/$assetName").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
        }
        val autoSkillsFile = File(localSkillsDir, "auto-skills.md")
        if (!autoSkillsFile.exists()) {
            runCatching {
                autoSkillsFile.writeText(
                    """
                    ---
                    name: auto_skills
                    description: CineCode-AI Autonomous Multi-Skill & Cloud MCP Orchestrator
                    ---
                    # Auto Skills (CineCode-AI)
                    Automatically coordinates local & cloud-native skills, NodeEmulated JS tools, and Google Search Grounding.
                    """.trimIndent()
                )
            }
        }

        // 2. Scan standalone skills/*.md directories
        val skillRoots = listOfNotNull(
            localSkillsDir,
            File(LocalEnvironmentManager.getHomeDir(appCtx), "skills")
        )
        for (dir in skillRoots) {
            if (!dir.isDirectory) continue
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
                .forEach { file ->
                    val raw = runCatching { file.readText() }.getOrDefault("")
                    val slug = file.nameWithoutExtension
                    val (title, desc) = ExtensionSkills.describe(raw, slug)
                    collected[slug] = LoadedSkillItem(
                        id = slug,
                        title = title,
                        description = desc.ifBlank { "Autonomous skill from ${file.name}" },
                        source = "skills/${file.name}",
                        filePath = file.absolutePath
                    )
                }
        }

        // 3. Scan plugin directories (browser-extensions, extensions, user projects)
        for (extDir in BrowserExtensionManager.instance.getExtensionsDirectories(appCtx)) {
            if (!extDir.isDirectory) continue
            for (pluginSub in extDir.listFiles().orEmpty()) {
                if (!pluginSub.isDirectory) continue
                for (skill in ExtensionSkills.list(pluginSub)) {
                    collected.putIfAbsent(
                        "${pluginSub.name}_${skill.name}",
                        LoadedSkillItem(
                            id = "${pluginSub.name}_${skill.name}",
                            title = skill.title,
                            description = skill.description.ifBlank { "Plugin skill (${pluginSub.name})" },
                            source = "${pluginSub.name}/skills/${skill.name}.md",
                            filePath = skill.path
                        )
                    )
                }
                // Also inspect JSON MCP manifest via JsBridgeInterpreter if Node is emulated
                val manifestFile = File(pluginSub, "manifest.json")
                if (manifestFile.isFile) {
                    LocalEnvironmentManager.JsBridgeInterpreter.emulatePluginRuntime(pluginSub)
                }
            }
        }

        val list = collected.values.toList()
        _loadedSkills.value = list
        _isActiveConnected.value = true
        _statusMessage.value = "✅ कौशल फ़ाइलें सिंक / Active & Connected (${list.size} skills active)"

        // Inject into CustomSlashCommands so AI tools can invoke them immediately
        registerIntoSlashCommands(appCtx, list)
        list
    }

    suspend fun forceUploadAndSyncAll(
        context: Context,
        repoSlug: String = DEFAULT_GITHUB_REPO
    ): List<LoadedSkillItem> = withContext(Dispatchers.IO) {
        if (_isSyncing.value) return@withContext _loadedSkills.value
        _isSyncing.value = true
        val appCtx = context.applicationContext

        try {
            // Ensure local NodeJS background setup is triggered silently if missing
            LocalEnvironmentManager.ensureNodeJsInBackground(appCtx)

            val localSkillsDir = File(appCtx.filesDir, "skills").apply { mkdirs() }
            var remoteSyncedCount = 0

            // Fetch skills from GitHub API directly (bypassing 127.0.0.1:1234)
            val cleanSlug = repoSlug.trim().removePrefix("https://github.com/").removeSuffix("/")
                .ifBlank { DEFAULT_GITHUB_REPO }

            for (folder in listOf("skills", "extensions/google-flow/skills")) {
                val apiUrl = "https://api.github.com/repos/$cleanSlug/contents/$folder"
                runCatching {
                    val req = Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "CineCode-AI-Android")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string().orEmpty()
                        if (!body.trimStart().startsWith("[")) return@use
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val name = item.optString("name")
                            val downloadUrl = item.optString("download_url")
                            if (item.optString("type") == "file" &&
                                (name.endsWith(".md", ignoreCase = true) || name.endsWith(".json", ignoreCase = true)) &&
                                downloadUrl.isNotBlank()
                            ) {
                                val content = downloadText(downloadUrl)
                                if (content != null) {
                                    File(localSkillsDir, name).writeText(content)
                                    remoteSyncedCount++
                                }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "GitHub skill sync skipped for $folder: ${it.message}")
                }
            }

            // Refresh BrowserExtensionManager & local skills + register CineCodeSkillsMcp tools
            runCatching { BrowserExtensionManager.instance.loadExtensions() }
            val mcpToolCount = com.shriram.cinecode.data.media.CineCodeSkillsMcp.tools().length()
            val allLoaded = scanLocalAndAssets(appCtx)
            _isActiveConnected.value = true
            _statusMessage.value = if (remoteSyncedCount > 0) {
                "✅ कौशल फ़ाइलें सिंक / Active & Connected (${allLoaded.size} skills, $mcpToolCount MCP tools, +$remoteSyncedCount GitHub synced)"
            } else {
                "✅ कौशल फ़ाइलें सिंक / Active & Connected (${allLoaded.size} skills, $mcpToolCount MCP tools active)"
            }
            allLoaded
        } finally {
            _isSyncing.value = false
        }
    }

    private fun downloadText(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "CineCode-AI-Android")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()

    private suspend fun registerIntoSlashCommands(context: Context, skills: List<LoadedSkillItem>) {
        withContext(Dispatchers.Main) {
            CustomSlashCommands.init(context)
            for (skill in skills) {
                val safeName = skill.id
                    .replace(Regex("[^A-Za-z0-9_-]"), "_")
                    .trimStart('_', '-')
                    .take(44)
                if (safeName.isBlank() || !CustomSlashCommands.NAME.matches(safeName)) continue
                if (CustomSlashCommands.find(safeName) != null) continue
                val body = runCatching { File(skill.filePath).readText().take(2400) }
                    .getOrDefault(skill.description)
                runCatching {
                    CustomSlashCommands.save(
                        CustomSlashCommand(
                            name = safeName,
                            description = skill.description.take(120),
                            expansion = body.ifBlank { skill.description }
                        )
                    )
                }
            }
        }
    }
}
