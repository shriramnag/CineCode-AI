package com.shriram.cinecode.data.remote.services

import com.shriram.cinecode.data.preferences.AuthPreferences
import com.shriram.cinecode.domain.model.McpServerSpec
import com.shriram.cinecode.domain.model.McpServerState
import com.shriram.cinecode.domain.model.McpToolInfo
import exa.language_server_pb.GetMcpServerStatesRequest
import exa.language_server_pb.RefreshMcpServersRequest
import exa.language_server_pb.ToggleMcpServerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated RPC service for Model Context Protocol (MCP) server states, refreshing, and toggling.
 * Uses typed Square Wire AgyLanguageService gRPC client.
 */
class AgyMcpService {
    companion object {
        private const val TAG = "AgyMcpService"
        val instance by lazy { AgyMcpService() }
    }

    suspend fun getMcpServerStates(hubUrl: String = AuthPreferences.currentHubUrl): Result<List<McpServerState>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = GetMcpServerStatesRequest()
            getAgyLanguageService(hubUrl).GetMcpServerStates().executeSafely(req).map { res ->
                res.states.map { state ->
                    val spec = state.spec
                    val name = spec?.server_name?.takeIf { it.isNotBlank() } ?: "mcp_server"
                    val envMap = spec?.env ?: emptyMap()
                    val headersMap = spec?.headers ?: emptyMap()
                    val disabled = spec?.disabled ?: false

                    val domainSpec = McpServerSpec(
                        serverName = name,
                        command = spec?.command ?: "",
                        args = spec?.args ?: emptyList(),
                        env = envMap,
                        serverUrl = spec?.server_url ?: "",
                        headers = headersMap,
                        disabled = disabled,
                        cwd = spec?.cwd ?: ""
                    )

                    val toolsList = state.tools.map { t ->
                        McpToolInfo(
                            name = t.name,
                            description = t.description,
                            inputSchema = t.json_schema_string
                        )
                    }

                    McpServerState(
                        name = name,
                        spec = domainSpec,
                        status = state.status.name,
                        error = state.error.takeIf { it.isNotBlank() },
                        tools = toolsList,
                        instructions = state.instructions.takeIf { it.isNotBlank() },
                        isEnabled = !disabled
                    )
                }
            }.getOrThrow()
        }
    }

    suspend fun refreshMcpServers(hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = RefreshMcpServersRequest()
            getAgyLanguageService(hubUrl).RefreshMcpServers().executeSafely(req).map { }.getOrThrow()
        }
    }

    suspend fun toggleMcpServer(serverName: String, enabled: Boolean, hubUrl: String = AuthPreferences.currentHubUrl): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = ToggleMcpServerRequest(
                server_name = serverName,
                enabled = enabled
            )
            getAgyLanguageService(hubUrl).ToggleMcpServer().executeSafely(req).map { }.getOrThrow()
        }
    }

    private val customProbeClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(10000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Lightweight non-blocking reachability probe for a Custom MCP Server URL (HTTP/SSE/JSON-RPC)
     * with smart 3G/4G timeout and 1 quick automatic retry so slow networks don't false-fail or hang.
     */
    suspend fun probeCustomMcpEndpoint(
        serverUrl: String,
        headers: Map<String, String> = emptyMap()
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = serverUrl.trim()
            require(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                "Custom MCP URL must start with http:// or https://"
            }
            val reqBuilder = okhttp3.Request.Builder()
                .url(trimmed)
                .header("Accept", "text/event-stream, application/json")
                .get()
            headers.forEach { (k, v) -> if (k.isNotBlank()) reqBuilder.header(k, v) }
            val request = reqBuilder.build()

            var lastErr: Exception? = null
            for (attempt in 1..2) {
                try {
                    customProbeClient.newCall(request).execute().use { resp ->
                        if (resp.code >= 500) {
                            throw IllegalStateException("Custom MCP server returned HTTP ${resp.code}")
                        }
                        return@runCatching resp.code
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    if (attempt < 2) kotlinx.coroutines.delay(600L)
                }
            }
            throw lastErr ?: IllegalStateException("Custom MCP server unreachable")
        }
    }
}
