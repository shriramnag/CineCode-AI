package com.shriram.cinecode.data.jarvis

import android.content.Context
import com.shriram.cinecode.data.daemon.ProjectItem
import com.shriram.cinecode.data.daemon.TermuxDaemonManager
import com.shriram.cinecode.data.github.GitHubManager
import com.shriram.cinecode.data.github.GitHubRepoItem
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Implements `jarvis_github_*` MCP tools using saved GitHub PAT ([GitHubManager])
 * and/or local terminal `gh` & `git` tooling.
 */
object JarvisGitHubHelper {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var cachedRepos: List<GitHubRepoItem> = emptyList()

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    suspend fun status(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val token = GitHubManager.getToken(context)
        val gitName = LocalEnvironmentManager.executeCommand("git config --global user.name 2>/dev/null || true", context, timeoutSeconds = 8).output.trim()
        val gitEmail = LocalEnvironmentManager.executeCommand("git config --global user.email 2>/dev/null || true", context, timeoutSeconds = 8).output.trim()
        val ghRes = LocalEnvironmentManager.executeCommand("command -v gh >/dev/null 2>&1 && gh auth status 2>&1 || echo '__NO_GH__'", context, timeoutSeconds = 10)
        val ghInstalled = !ghRes.output.contains("__NO_GH__")
        val ghLoggedIn = ghInstalled && ghRes.exitCode == 0

        val out = JSONObject()
        out.put("pat_configured", token.isNotBlank())
        out.put("gh_installed", ghInstalled)
        out.put("gh_signed_in", ghLoggedIn)
        if (gitName.isNotBlank()) out.put("git_user_name", gitName)
        if (gitEmail.isNotBlank()) out.put("git_user_email", gitEmail)

        if (token.isNotBlank()) {
            runCatching {
                val req = Request.Builder()
                    .url("https://api.github.com/user")
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "CineCode-AI-Android")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val u = JSONObject(raw)
                        out.put("signed_in", true)
                        out.put("username", u.optString("login"))
                        u.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { out.put("name", it) }
                        u.optString("email").takeIf { it.isNotBlank() && it != "null" }?.let { out.put("email", it) }
                        out.put("public_repos", u.optInt("public_repos", 0))
                        out.put("private_repos", u.optInt("total_private_repos", 0))
                    } else {
                        out.put("signed_in", ghLoggedIn)
                        out.put("api_error", "HTTP ${resp.code}: ${raw.take(150)}")
                    }
                }
            }.onFailure {
                out.put("signed_in", ghLoggedIn)
                out.put("api_error", it.message ?: "network error")
            }
        } else {
            out.put("signed_in", ghLoggedIn)
            if (ghLoggedIn) {
                val user = LocalEnvironmentManager.executeCommand("gh api user -q .login 2>/dev/null || true", context, timeoutSeconds = 10).output.trim()
                if (user.isNotBlank()) out.put("username", user)
            } else {
                out.put("hint", "Sign in via CineCode-AI → Settings → GitHub (PAT) or run `gh auth login` in the terminal.")
            }
        }
        out
    }

    suspend fun listRepos(
        context: Context,
        query: String,
        reload: Boolean
    ): Result<List<GitHubRepoItem>> = withContext(Dispatchers.IO) {
        if (reload || cachedRepos.isEmpty()) {
            val token = GitHubManager.getToken(context)
            val loaded = if (token.isNotBlank()) {
                GitHubManager.listMyRepositories(context)
            } else {
                runCatching {
                    val res = LocalEnvironmentManager.executeCommand(
                        "gh repo list --limit 100 --json nameWithOwner,description,primaryLanguage,stargazerCount,url",
                        context,
                        timeoutSeconds = 20
                    )
                    if (res.exitCode != 0) error(res.output.ifBlank { "Sign in to GitHub first (Settings → GitHub or `gh auth login`)." })
                    val arr = JSONArray(res.output.trim())
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val full = o.optString("nameWithOwner")
                        val url = o.optString("url")
                        GitHubRepoItem(
                            fullName = full,
                            description = o.optString("description").takeIf { it != "null" }.orEmpty(),
                            stars = o.optInt("stargazerCount", 0),
                            language = o.optJSONObject("primaryLanguage")?.optString("name").orEmpty(),
                            cloneUrl = if (url.endsWith(".git")) url else "$url.git",
                            htmlUrl = url
                        )
                    }
                }
            }
            if (loaded.isFailure) return@withContext loaded
            cachedRepos = loaded.getOrThrow()
        }

        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) cachedRepos else cachedRepos.filter { r ->
            r.fullName.lowercase().contains(q) ||
                r.description.lowercase().contains(q) ||
                r.language.lowercase().contains(q)
        }
        Result.success(filtered)
    }

    suspend fun cloneRepo(
        context: Context,
        repoArg: String,
        folderArg: String?,
        openProject: Boolean,
        showIde: Boolean,
        onOpenProject: (ProjectItem, Boolean) -> Unit
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = repoArg.trim()
            require(trimmed.isNotEmpty()) { "repo is required" }

            var cloneUrl: String
            var repoName: String
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("git@")) {
                cloneUrl = trimmed
                repoName = trimmed.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
            } else if (trimmed.contains('/')) {
                val slug = trimmed.removeSuffix(".git")
                cloneUrl = "https://github.com/$slug.git"
                repoName = slug.substringAfterLast('/')
            } else {
                val matched = listRepos(context, "", reload = cachedRepos.isEmpty()).getOrNull()
                    ?.firstOrNull { it.fullName.substringAfterLast('/').equals(trimmed, ignoreCase = true) }
                if (matched != null) {
                    cloneUrl = matched.cloneUrl
                    repoName = matched.fullName.substringAfterLast('/')
                } else {
                    val user = status(context).optString("username").trim()
                    if (user.isNotBlank()) {
                        cloneUrl = "https://github.com/$user/$trimmed.git"
                        repoName = trimmed
                    } else {
                        error("Repository \"$trimmed\" not found and no GitHub user signed in. Pass owner/repo or https://github.com/... URL.")
                    }
                }
            }

            val folderName = folderArg?.trim()?.takeIf { it.isNotEmpty() } ?: repoName.ifBlank { "repo" }
            val homeDir = LocalEnvironmentManager.getHomeDir(context).apply { mkdirs() }
            val dest = File(homeDir, folderName)
            if (dest.exists()) error("Folder ${dest.absolutePath} already exists (refusing to overwrite).")

            val token = GitHubManager.getToken(context)
            val authedUrl = if (token.isNotBlank() && cloneUrl.startsWith("https://github.com/")) {
                cloneUrl.replaceFirst("https://github.com/", "https://x-access-token:$token@github.com/")
            } else {
                cloneUrl
            }

            val res = LocalEnvironmentManager.executeCommand(
                command = "git clone ${shellQuote(authedUrl)} ${shellQuote(dest.absolutePath)}",
                context = context,
                workingDir = homeDir.absolutePath,
                timeoutSeconds = 180
            )
            if (res.exitCode != 0) {
                error("git clone failed: ${res.output.replace(token, "***").take(500)}")
            }
            // Strip embedded PAT from origin URL on disk so .git/config does not store raw PAT
            if (token.isNotBlank() && authedUrl != cloneUrl) {
                LocalEnvironmentManager.executeCommand(
                    command = "git -C ${shellQuote(dest.absolutePath)} remote set-url origin ${shellQuote(cloneUrl)}",
                    context = context,
                    timeoutSeconds = 10
                )
            }

            val project = ProjectItem(dest.name, dest.absolutePath)
            if (openProject) {
                onOpenProject(project, showIde)
            }

            JSONObject()
                .put("status", "cloned")
                .put("repo", cloneUrl)
                .put("path", dest.absolutePath)
                .put("opened_in_ide", openProject)
        }
    }

    suspend fun createRepo(
        context: Context,
        nameArg: String,
        isPrivate: Boolean,
        description: String,
        projectDir: String?,
        push: Boolean,
        remote: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val rawName = nameArg.trim()
            require(rawName.isNotEmpty()) { "Repository name is required." }
            val token = GitHubManager.getToken(context)

            var htmlUrl = ""
            var cloneUrl = ""
            var fullName = rawName

            if (token.isNotBlank()) {
                val (endpoint, repoName) = if (rawName.contains('/')) {
                    val parts = rawName.split('/', limit = 2)
                    "https://api.github.com/orgs/${parts[0]}/repos" to parts[1]
                } else {
                    "https://api.github.com/user/repos" to rawName
                }
                val payload = JSONObject()
                    .put("name", repoName)
                    .put("private", isPrivate)
                    .apply { if (description.isNotBlank()) put("description", description) }
                val req = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "CineCode-AI-Android")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("GitHub create repo HTTP ${resp.code}: ${raw.take(250)}")
                    val obj = JSONObject(raw)
                    fullName = obj.optString("full_name", rawName)
                    cloneUrl = obj.optString("clone_url")
                    htmlUrl = obj.optString("html_url")
                }
            } else {
                val visFlag = if (isPrivate) "--private" else "--public"
                val descFlag = if (description.isNotBlank()) "--description ${shellQuote(description)}" else ""
                val res = LocalEnvironmentManager.executeCommand(
                    command = "gh repo create ${shellQuote(rawName)} $visFlag $descFlag",
                    context = context,
                    timeoutSeconds = 30
                )
                if (res.exitCode != 0) {
                    error("Sign in via Settings → GitHub (or `gh auth login`) first: ${res.output.take(300)}")
                }
                htmlUrl = res.output.trim().lines().lastOrNull { it.startsWith("http") }.orEmpty()
                cloneUrl = if (htmlUrl.isNotBlank()) "$htmlUrl.git" else "https://github.com/$rawName.git"
            }

            var pushed = false
            var pushNote: String? = null
            if (!projectDir.isNullOrBlank() && File(projectDir).isDirectory && cloneUrl.isNotBlank()) {
                val safeDir = shellQuote(projectDir)
                val safeRemote = shellQuote(remote.ifBlank { "origin" })
                LocalEnvironmentManager.executeCommand(
                    command = "cd $safeDir && ([ -d .git ] || git init) && (git remote get-url $safeRemote >/dev/null 2>&1 && git remote set-url $safeRemote ${shellQuote(cloneUrl)} || git remote add $safeRemote ${shellQuote(cloneUrl)})",
                    context = context,
                    timeoutSeconds = 15
                )
                if (push) {
                    val pushUrl = if (token.isNotBlank() && cloneUrl.startsWith("https://github.com/")) {
                        cloneUrl.replaceFirst("https://github.com/", "https://x-access-token:$token@github.com/")
                    } else {
                        cloneUrl
                    }
                    val pushRes = LocalEnvironmentManager.executeCommand(
                        command = "cd $safeDir && if git rev-parse --verify HEAD >/dev/null 2>&1; then git push -u ${shellQuote(pushUrl)} HEAD; else echo '__NO_COMMITS__'; fi",
                        context = context,
                        timeoutSeconds = 90
                    )
                    if (pushRes.output.contains("__NO_COMMITS__")) {
                        pushNote = "Linked $remote, no commits yet to push (run git add & git commit first)."
                    } else if (pushRes.exitCode == 0) {
                        pushed = true
                    } else {
                        pushNote = "Linked $remote, push output: ${pushRes.output.replace(token, "***").take(250)}"
                    }
                }
            }

            cachedRepos = emptyList()
            JSONObject()
                .put("status", "created")
                .put("full_name", fullName)
                .put("private", isPrivate)
                .put("clone_url", cloneUrl)
                .put("html_url", htmlUrl)
                .put("pushed", pushed)
                .apply { if (pushNote != null) put("note", pushNote) }
        }
    }
}
