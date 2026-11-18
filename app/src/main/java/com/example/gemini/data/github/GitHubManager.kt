package com.shriram.cinecode.data.github

import android.content.Context
import com.shriram.cinecode.data.daemon.GitApiClient
import com.shriram.cinecode.data.daemon.GitActionResult
import com.shriram.cinecode.data.daemon.GitStatusResponse
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GitHubRepoItem(
    val fullName: String,
    val description: String,
    val stars: Int,
    val language: String,
    val cloneUrl: String,
    val htmlUrl: String
)

/**
 * GitHub Manager & Git Sync helper for CineCode-AI.
 * Searches public/user GitHub repositories and syncs local workspace repos via GitApiClient / local git.
 */
object GitHubManager {
    private const val PREFS = "cinecode_github_prefs"
    private const val KEY_TOKEN = "github_pat"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun getToken(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun saveToken(context: Context, token: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    suspend fun searchRepositories(context: Context, query: String): Result<List<GitHubRepoItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.trim().ifBlank { "cinecode" }
            val encoded = URLEncoder.encode(q, "UTF-8")
            val token = getToken(context)
            val reqBuilder = Request.Builder()
                .url("https://api.github.com/search/repositories?q=$encoded&sort=stars&per_page=15")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CineCode-AI-Android")
            if (token.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $token")
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("GitHub API ${resp.code}: ${raw.take(180)}")
                val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
                parseRepos(items)
            }
        }
    }

    suspend fun listMyRepositories(context: Context): Result<List<GitHubRepoItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getToken(context)
            require(token.isNotBlank()) { "Add a free GitHub Personal Access Token first to list your private/personal repos." }
            val req = Request.Builder()
                .url("https://api.github.com/user/repos?sort=updated&per_page=25")
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", "CineCode-AI-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("GitHub API ${resp.code}: ${raw.take(180)}")
                parseRepos(JSONArray(raw))
            }
        }
    }

    suspend fun cloneIntoWorkspace(context: Context, cloneUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val projectsDir = LocalEnvironmentManager.getProjectsDir(context).apply { mkdirs() }
            val repoName = cloneUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
            val targetDir = File(projectsDir, repoName)
            val res = LocalEnvironmentManager.executeCommand(
                command = "git clone \"${cloneUrl.replace("\"", "\\\"")}\" \"${targetDir.absolutePath}\"",
                context = context,
                workingDir = projectsDir.absolutePath,
                timeoutSeconds = 120
            )
            if (res.exitCode != 0) error(res.output.ifBlank { "git clone failed with exit ${res.exitCode}" })
            targetDir.absolutePath
        }
    }

    suspend fun getProjectGitStatus(projectPath: String): GitStatusResponse? =
        GitApiClient.getStatus(projectPath)

    suspend fun pullProject(projectPath: String): GitActionResult {
        val ok = GitApiClient.pull(projectPath)
        return if (ok) GitActionResult(success = true, output = "Pull completed")
        else GitActionResult(success = false, error = "Pull failed (check bridge / branch)")
    }

    suspend fun pushProject(projectPath: String): GitActionResult {
        val ok = GitApiClient.push(projectPath)
        return if (ok) GitActionResult(success = true, output = "Push completed")
        else GitActionResult(success = false, error = "Push failed (check bridge / remote)")
    }

    private fun parseRepos(arr: JSONArray): List<GitHubRepoItem> = buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                GitHubRepoItem(
                    fullName = o.optString("full_name"),
                    description = o.optString("description", "").takeIf { it != "null" } ?: "",
                    stars = o.optInt("stargazers_count", 0),
                    language = o.optString("language", "").takeIf { it != "null" } ?: "",
                    cloneUrl = o.optString("clone_url"),
                    htmlUrl = o.optString("html_url")
                )
            )
        }
    }
}
