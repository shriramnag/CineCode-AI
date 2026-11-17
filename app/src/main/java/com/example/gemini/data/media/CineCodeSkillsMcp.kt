package com.shriram.cinecode.data.media

import android.content.Context
import com.example.gemini.data.remote.GeminiSearchGroundingClient
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import com.shriram.cinecode.data.local.LocalSandboxExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CineCodeSkillsMcp: Unified MCP Tools schema and dispatcher for the 5 advanced creator & coding skills:
 * execute_local_code, camera_director, fetch_epic_multimedia, find_background_assets, and auto_git_push.
 */
object CineCodeSkillsMcp {

    const val INSTRUCTIONS = "CineCode-AI Creator & Autonomous Sandbox MCP: " +
        "Use execute_local_code to safely run & self-heal Python/Node.js/HTML code in sandbox_workspace, " +
        "camera_director to build 3D/Mythological camera & lighting prompts, " +
        "fetch_epic_multimedia and find_background_assets to research royalty-free media and timeline assets, " +
        "and auto_git_push to commit/push verified project work."

    private fun prop(type: String, description: String) = JSONObject()
        .put("type", type)
        .put("description", description)

    private fun toolSchema(
        name: String,
        description: String,
        required: List<String>,
        vararg properties: Pair<String, JSONObject>
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("inputSchema", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                properties.forEach { (k, v) -> put(k, v) }
            })
            put("required", JSONArray(required))
        })
    }

    fun tools(): JSONArray = JSONArray().apply {
        put(
            toolSchema(
                name = "execute_local_code",
                description = "Execute Python, Node.js/JS, or HTML/JS code inside the safe sandbox_workspace directory with live stdout/stderr capture and automatic Self-Healing diagnostics.",
                required = listOf("language", "code"),
                "language" to prop("string", "Target runtime: 'python', 'nodejs', or 'html'"),
                "code" to prop("string", "Source code to execute inside the sandbox"),
                "filename" to prop("string", "Optional filename inside sandbox_workspace"),
                "auto_self_heal" to prop("boolean", "Auto-repair syntax/indentation errors on failure (default true)")
            )
        )
        put(
            toolSchema(
                name = "camera_director",
                description = "CineCameraDirector: Generate 3D Cinematic & Indian Mythological camera movement (Pan, Zoom, Orbit, Crane), ambient divine lighting, and multi-layer depth prompts.",
                required = listOf("scene_description"),
                "scene_description" to prop("string", "Scene narration or visual action description"),
                "style" to prop("string", "Visual style: 'mythological_3d', '2d_realistic', or 'futuristic_2050'"),
                "movement" to prop("string", "Optional camera movement hint (e.g., Orbit, Pan, Dolly, Crane, Zoom)"),
                "lighting" to prop("string", "Optional ambient lighting hint (e.g., Divine Golden Aura, Vedic Dawn, Cosmic Astral)")
            )
        )
        put(
            toolSchema(
                name = "fetch_epic_multimedia",
                description = "Autonomous Google Search Grounding research & royalty-free historical image, ambient music, and archaeological fact finder for AI video timelines.",
                required = listOf("topic"),
                "topic" to prop("string", "Historical, mythological, or documentary topic to research"),
                "media_type" to prop("string", "Filter: 'all', 'images', 'music', or 'facts'")
            )
        )
        put(
            toolSchema(
                name = "find_background_assets",
                description = "Find copyright-free ambient music, historical backdrops, and timeline cues matching a scene prompt.",
                required = listOf("scene_prompt"),
                "scene_prompt" to prop("string", "Scene description or mood to match background assets against")
            )
        )
        put(
            toolSchema(
                name = "auto_git_push",
                description = "Stage, commit, and push verified changes inside the local Termux/Ubuntu git project repository.",
                required = listOf("commit_message"),
                "commit_message" to prop("string", "Git commit message"),
                "repo_dir" to prop("string", "Optional path to local git workspace (defaults to CineCode-AI home folder)"),
                "branch" to prop("string", "Optional branch name (defaults to current branch)")
            )
        )
    }

    suspend fun call(context: Context, toolName: String, args: JSONObject): JSONObject {
        val resultText = runCatching {
            when (toolName) {
                "execute_local_code" -> {
                    val language = args.optString("language", "python")
                    val code = args.optString("code", "")
                    val filename = args.optString("filename", "").ifBlank { null }
                    val autoHeal = args.optBoolean("auto_self_heal", true)
                    val res = LocalSandboxExecutor.executeLocalCode(
                        context = context,
                        language = language,
                        code = code,
                        filename = filename,
                        autoSelfHeal = autoHeal
                    )
                    res.toJson().toString(2)
                }
                "camera_director" -> {
                    val scene = args.optString("scene_description", "")
                    val style = args.optString("style", "mythological_3d")
                    val movement = args.optString("movement", "").ifBlank { null }
                    val lighting = args.optString("lighting", "").ifBlank { null }
                    CineCameraDirector.directScene(scene, style, movement, lighting).toJson().toString(2)
                }
                "fetch_epic_multimedia" -> {
                    val topic = args.optString("topic", "")
                    val mediaType = args.optString("media_type", "all")
                    val res = GeminiSearchGroundingClient(context).fetchEpicMultimedia(topic, mediaType)
                    res.getOrElse { throw it }.toJson().toString(2)
                }
                "find_background_assets" -> {
                    val scene = args.optString("scene_prompt", "")
                    val res = GeminiSearchGroundingClient(context).findBackgroundAssets(scene)
                    res.getOrElse { throw it }.toJson().toString(2)
                }
                "auto_git_push" -> {
                    val commitMessage = args.optString("commit_message", "CineCode-AI automated update").trim()
                    val repoDirArg = args.optString("repo_dir", "").trim()
                    val branchArg = args.optString("branch", "").trim()
                    runAutoGitPush(context, commitMessage, repoDirArg, branchArg).toString(2)
                }
                else -> error("Unknown CineCode skill tool: $toolName")
            }
        }

        return resultText.fold(
            onSuccess = { text ->
                JSONObject().apply {
                    put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                    put("isError", false)
                }
            },
            onFailure = { err ->
                JSONObject().apply {
                    put("content", JSONArray().put(JSONObject().put("type", "text").put("text", err.message ?: "Skill execution failed")))
                    put("isError", true)
                }
            }
        )
    }

    private suspend fun runAutoGitPush(
        context: Context,
        commitMessage: String,
        repoDirArg: String,
        branchArg: String
    ): JSONObject {
        val homeDir = LocalEnvironmentManager.getHomeDir(context)
        val candidates = listOfNotNull(
            repoDirArg.takeIf { it.isNotBlank() }?.let(::File),
            File(homeDir, "CineCode-AI"),
            homeDir
        )
        val gitDir = candidates.firstOrNull { File(it, ".git").exists() }
        if (gitDir == null) {
            return JSONObject()
                .put("ok", false)
                .put("error", "No local git repository found in ${homeDir.absolutePath}")
        }
        val safeMsg = commitMessage.replace("\"", "'")
        val pushBranch = if (branchArg.isNotBlank()) "origin \"$branchArg\"" else ""
        val cmd = "git add -A && git commit -m \"$safeMsg\" && git push $pushBranch".trim()
        val cmdResult = LocalEnvironmentManager.executeCommand(
            command = cmd,
            context = context,
            workingDir = gitDir.absolutePath,
            timeoutSeconds = 60
        )
        return JSONObject()
            .put("ok", cmdResult.exitCode == 0)
            .put("repoDir", gitDir.absolutePath)
            .put("exitCode", cmdResult.exitCode)
            .put("output", cmdResult.output.trim())
            .put("durationMs", cmdResult.durationMs)
    }
}
