package com.shriram.cinecode.data.agent.agy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shriram.cinecode.data.preferences.AuthPreferences
import com.shriram.cinecode.data.remote.AgyHubClient
import com.shriram.cinecode.data.remote.AntigravityApiService
import com.shriram.cinecode.data.remote.GoogleOAuthManager
import com.shriram.cinecode.data.remote.StreamEvent
import com.shriram.cinecode.domain.model.AiModel
import com.shriram.cinecode.domain.model.ChatMessage
import com.shriram.cinecode.domain.model.ChatAttachment
import com.shriram.cinecode.domain.model.Conversation
import com.shriram.cinecode.domain.model.MessageRole
import com.shriram.cinecode.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import com.shriram.cinecode.data.agent.ChatSessionStore
import kotlinx.coroutines.CoroutineScope

/** Antigravity MCP servers and the Cascade MCP plugin marketplace. Moved out of ChatViewModel unchanged. */
class AgyMcpManager(
    private val backendScope: CoroutineScope,
    private val agyHubClient: AgyHubClient
) {

    private val _mcpServers = MutableStateFlow<List<com.shriram.cinecode.domain.model.McpServerState>>(emptyList())
    val mcpServers: StateFlow<List<com.shriram.cinecode.domain.model.McpServerState>> = _mcpServers.asStateFlow()

    private val _isMcpLoading = MutableStateFlow(false)
    val isMcpLoading: StateFlow<Boolean> = _isMcpLoading.asStateFlow()

    private val _isMcpRefreshing = MutableStateFlow(false)
    val isMcpRefreshing: StateFlow<Boolean> = _isMcpRefreshing.asStateFlow()

    private val _refreshingMcpServer = MutableStateFlow<String?>(null)
    val refreshingMcpServer: StateFlow<String?> = _refreshingMcpServer.asStateFlow()

    enum class McpConnectionStatus(val label: String) {
        CONNECTED("Connected"),
        RETRYING("Retrying…"),
        DISCONNECTED("Disconnected")
    }

    private val _mcpConnectionStatus = MutableStateFlow(McpConnectionStatus.DISCONNECTED)
    val mcpConnectionStatus: StateFlow<McpConnectionStatus> = _mcpConnectionStatus.asStateFlow()

    private val _customMcpStatus = MutableStateFlow(McpConnectionStatus.DISCONNECTED)
    val customMcpStatus: StateFlow<McpConnectionStatus> = _customMcpStatus.asStateFlow()

    private val _customMcpStatusDetail = MutableStateFlow<String?>(null)
    val customMcpStatusDetail: StateFlow<String?> = _customMcpStatusDetail.asStateFlow()

    private val _mcpErrorMessage = MutableStateFlow<String?>(null)
    val mcpErrorMessage: StateFlow<String?> = _mcpErrorMessage.asStateFlow()

    private val _mcpStatusMessage = MutableStateFlow<String?>(null)
    val mcpStatusMessage: StateFlow<String?> = _mcpStatusMessage.asStateFlow()

    fun clearMcpStatus() {
        _mcpStatusMessage.value = null
        _mcpErrorMessage.value = null
    }

    private val _availableCascadePlugins = MutableStateFlow<List<com.shriram.cinecode.data.remote.dto.AvailableCascadePluginDto>>(emptyList())
    val availableCascadePlugins: StateFlow<List<com.shriram.cinecode.data.remote.dto.AvailableCascadePluginDto>> = _availableCascadePlugins.asStateFlow()

    private val _isCascadePluginsLoading = MutableStateFlow(false)
    val isCascadePluginsLoading: StateFlow<Boolean> = _isCascadePluginsLoading.asStateFlow()

    private val _installingCascadePluginId = MutableStateFlow<String?>(null)
    val installingCascadePluginId: StateFlow<String?> = _installingCascadePluginId.asStateFlow()

    fun loadMcpServers() {
        backendScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            try {
                val hubUrl = AuthPreferences.currentHubUrl

                // 1. Fetch live states from AGY daemon (with one lightweight retry for transient restarts)
                var liveResult = agyHubClient.getMcpServerStates(hubUrl)
                if (liveResult.isFailure) {
                    _mcpConnectionStatus.value = McpConnectionStatus.RETRYING
                    delay(700)
                    liveResult = agyHubClient.getMcpServerStates(hubUrl)
                }
                var fetchError: String? = null
                if (liveResult.isFailure) {
                    val err = liveResult.exceptionOrNull()?.message ?: "Failed to connect to Antigravity Hub"
                    Log.w("ChatViewModel", "loadMcpServers liveResult failed: $err")
                    fetchError = err
                    _mcpConnectionStatus.value = McpConnectionStatus.DISCONNECTED
                } else {
                    _mcpConnectionStatus.value = McpConnectionStatus.CONNECTED
                }
                val liveList = liveResult.getOrDefault(emptyList()).toMutableList()

                // 2. Read persistent config from mcp_config.json to ensure any unstarted / disabled servers are also represented
                val configRaw = com.shriram.cinecode.data.daemon.IdeApiClient.getMcpConfig()
                if (!configRaw.isNullOrBlank()) {
                    try {
                        val cfgJson = org.json.JSONObject(configRaw)
                        val serversObj = cfgJson.optJSONObject("mcpServers")
                        if (serversObj != null) {
                            val keys = serversObj.keys()
                            while (keys.hasNext()) {
                                val serverName = keys.next()
                                val sObj = serversObj.optJSONObject(serverName) ?: continue
                                val existing = liveList.find { it.name.equals(serverName, ignoreCase = true) }
                                val disabled = sObj.optBoolean("disabled", false)

                                val command = sObj.optString("command", "")
                                val argsList = mutableListOf<String>()
                                sObj.optJSONArray("args")?.let { arr ->
                                    for (i in 0 until arr.length()) argsList.add(arr.optString(i))
                                }
                                val envMap = mutableMapOf<String, String>()
                                sObj.optJSONObject("env")?.let { envObj ->
                                    val envKeys = envObj.keys()
                                    while (envKeys.hasNext()) {
                                        val k = envKeys.next()
                                        envMap[k] = envObj.optString(k, "")
                                    }
                                }
                                val serverUrl = sObj.optString("serverUrl", "")
                                val headersMap = mutableMapOf<String, String>()
                                sObj.optJSONObject("headers")?.let { hObj ->
                                    val hKeys = hObj.keys()
                                    while (hKeys.hasNext()) {
                                        val k = hKeys.next()
                                        headersMap[k] = hObj.optString(k, "")
                                    }
                                }
                                val cwd = sObj.optString("cwd", "")

                                val parsedSpec = com.shriram.cinecode.domain.model.McpServerSpec(
                                    serverName = serverName,
                                    command = command,
                                    args = argsList,
                                    env = envMap,
                                    serverUrl = serverUrl,
                                    headers = headersMap,
                                    disabled = disabled,
                                    cwd = cwd
                                )

                                if (existing == null) {
                                    liveList.add(
                                        com.shriram.cinecode.domain.model.McpServerState(
                                            name = serverName,
                                            spec = parsedSpec,
                                            status = if (disabled) "DISABLED" else "MCP_SERVER_STATUS_STOPPED",
                                            isEnabled = !disabled
                                        )
                                    )
                                } else {
                                    val updatedSpec = existing.spec ?: parsedSpec
                                    val idx = liveList.indexOf(existing)
                                    liveList[idx] = existing.copy(
                                        spec = updatedSpec,
                                        isEnabled = !updatedSpec.disabled
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error parsing mcp_config.json: ${e.message}")
                    }
                }

                if (fetchError != null) {
                    _mcpErrorMessage.value = "Cannot reach Antigravity daemon ($hubUrl): $fetchError"
                }

                // If daemon RPC failed and no servers found, preserve existing cached servers if available
                if (liveList.isEmpty() && fetchError != null && _mcpServers.value.isNotEmpty()) {
                    Log.w("ChatViewModel", "Retaining ${_mcpServers.value.size} cached MCP servers due to fetch failure")
                } else {
                    // Stable alphabetical sorting by name (case-insensitive) to prevent jumping
                    _mcpServers.value = liveList.sortedBy { it.name.lowercase() }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "loadMcpServers error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun refreshMcpServers(targetServer: String? = null) {
        backendScope.launch {
            _isMcpRefreshing.value = true
            _refreshingMcpServer.value = targetServer
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val hubUrl = AuthPreferences.currentHubUrl
                val res = agyHubClient.refreshMcpServers(hubUrl)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()?.message ?: "Refresh failed"
                    _mcpErrorMessage.value = "Daemon refresh error ($hubUrl): $err"
                }
                delay(600)
                loadMcpServers()
                if (targetServer != null) {
                    val s = _mcpServers.value.find { it.name.equals(targetServer, ignoreCase = true) }
                    if (s != null && !s.error.isNullOrBlank()) {
                        _mcpErrorMessage.value = "'$targetServer': ${s.error}"
                    } else if (s != null && s.tools.isNotEmpty()) {
                        _mcpStatusMessage.value = "Refreshed '$targetServer': ${s.tools.size} tool(s) available"
                    } else {
                        _mcpStatusMessage.value = "Refreshed '$targetServer'"
                    }
                } else {
                    val count = _mcpServers.value.size
                    val toolsCount = _mcpServers.value.sumOf { it.tools.size }
                    _mcpStatusMessage.value = "Refreshed: $count server(s) configured, $toolsCount tool(s) discovered"
                }
            } catch (e: Exception) {
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpRefreshing.value = false
                _refreshingMcpServer.value = null
            }
        }
    }

    fun toggleMcpServer(serverName: String, enabled: Boolean) {
        backendScope.launch {
            val hubUrl = AuthPreferences.currentHubUrl

            // 1. Call daemon ToggleMcpServer
            agyHubClient.toggleMcpServer(serverName, enabled, hubUrl)

            // 2. Persist disabled state in mcp_config.json
            try {
                val rawConfig = com.shriram.cinecode.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val mcpServers = json.optJSONObject("mcpServers") ?: org.json.JSONObject().also { json.put("mcpServers", it) }
                val targetServer = mcpServers.optJSONObject(serverName)
                if (targetServer != null) {
                    targetServer.put("disabled", !enabled)
                    com.shriram.cinecode.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Failed to update disabled flag in mcp_config.json: ${e.message}")
            }

            // 3. Update local state with stable sort
            _mcpServers.value = _mcpServers.value.map {
                if (it.name.equals(serverName, ignoreCase = true)) {
                    val updatedSpec = it.spec?.copy(disabled = !enabled)
                    it.copy(
                        spec = updatedSpec,
                        isEnabled = enabled,
                        status = if (!enabled) "DISABLED" else it.status
                    )
                } else it
            }.sortedBy { it.name.lowercase() }
            _mcpStatusMessage.value = if (enabled) "Enabled '$serverName'" else "Disabled '$serverName'"
        }
    }

    fun saveMcpServer(spec: com.shriram.cinecode.domain.model.McpServerSpec, rawJsonString: String? = null) {
        backendScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val rawConfig = com.shriram.cinecode.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val serversObj = json.optJSONObject("mcpServers") ?: org.json.JSONObject().also { json.put("mcpServers", it) }

                val serverObj: org.json.JSONObject = if (!rawJsonString.isNullOrBlank()) {
                    org.json.JSONObject(rawJsonString)
                } else {
                    org.json.JSONObject().apply {
                        if (spec.serverUrl.isNotBlank()) {
                            put("serverUrl", spec.serverUrl)
                            if (spec.headers.isNotEmpty()) {
                                val hObj = org.json.JSONObject()
                                spec.headers.forEach { (k, v) -> hObj.put(k, v) }
                                put("headers", hObj)
                            }
                        } else {
                            put("command", spec.command)
                            if (spec.args.isNotEmpty()) {
                                val argsArr = org.json.JSONArray()
                                spec.args.forEach { argsArr.put(it) }
                                put("args", argsArr)
                            }
                            if (spec.cwd.isNotBlank()) {
                                put("cwd", spec.cwd)
                            }
                            val envMap = spec.env.toMutableMap()
                            // Ensure Termux stdio binaries (npx, python3, etc.) have proper loader and PATH on Android.
                            // A server runs the MCP on its own Linux: those Termux paths would break it there.
                            if (!AuthPreferences.currentRemoteMode) {
                                val appPkg = com.shriram.cinecode.BuildConfig.APPLICATION_ID
                                if (!envMap.containsKey("LD_PRELOAD")) {
                                    envMap["LD_PRELOAD"] = "/data/data/$appPkg/files/usr/lib/libtermux-exec.so"
                                }
                                if (!envMap.containsKey("PATH")) {
                                    envMap["PATH"] = "/data/data/$appPkg/files/home/.local/bin:/data/data/$appPkg/files/usr/bin:/system/bin"
                                }
                            }
                            if (envMap.isNotEmpty()) {
                                val envObj = org.json.JSONObject()
                                envMap.forEach { (k, v) -> envObj.put(k, v) }
                                put("env", envObj)
                            }
                        }
                        if (spec.disabled) {
                            put("disabled", true)
                        }
                    }
                }
                serversObj.put(spec.serverName, serverObj)

                val success = com.shriram.cinecode.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                if (!success) {
                    _mcpErrorMessage.value = "Failed to save configuration to mcp_config.json"
                } else {
                    val hubUrl = AuthPreferences.currentHubUrl
                    agyHubClient.refreshMcpServers(hubUrl)
                    loadMcpServers()
                    _mcpStatusMessage.value = "Saved '${spec.serverName}'. Tap Refresh on the server to connect."
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "saveMcpServer error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun deleteMcpServer(serverName: String) {
        backendScope.launch {
            _isMcpLoading.value = true
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                // Instantly remove from local list for snappy UI
                _mcpServers.value = _mcpServers.value.filterNot { it.name.equals(serverName, ignoreCase = true) }

                val rawConfig = com.shriram.cinecode.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
                val json = if (rawConfig.trim().startsWith("{")) org.json.JSONObject(rawConfig) else org.json.JSONObject()
                val serversObj = json.optJSONObject("mcpServers")
                if (serversObj != null && serversObj.has(serverName)) {
                    serversObj.remove(serverName)
                    com.shriram.cinecode.data.daemon.IdeApiClient.saveMcpConfig(json.toString(2))
                }

                val hubUrl = AuthPreferences.currentHubUrl
                agyHubClient.refreshMcpServers(hubUrl)
                loadMcpServers()
                _mcpStatusMessage.value = "Removed '$serverName'."
            } catch (e: Exception) {
                Log.e("ChatViewModel", "deleteMcpServer error: ${e.message}", e)
                _mcpErrorMessage.value = e.message
            } finally {
                _isMcpLoading.value = false
            }
        }
    }

    fun loadAvailableCascadePlugins(query: String = "") {
        backendScope.launch {
            _isCascadePluginsLoading.value = true
            try {
                val res = agyHubClient.getAvailableCascadePlugins(os = "linux", searchQuery = query)
                if (res.isSuccess) {
                    _availableCascadePlugins.value = res.getOrDefault(emptyList())
                } else {
                    Log.w("ChatViewModel", "loadAvailableCascadePlugins error: ${res.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "loadAvailableCascadePlugins failed: ${e.message}", e)
            } finally {
                _isCascadePluginsLoading.value = false
            }
        }
    }

    fun installCascadeMcpPlugin(plugin: com.shriram.cinecode.data.remote.dto.AvailableCascadePluginDto) {
        backendScope.launch {
            _installingCascadePluginId.value = plugin.id
            _mcpErrorMessage.value = null
            _mcpStatusMessage.value = null
            try {
                val res = agyHubClient.installCascadePlugin(plugin)
                if (res.isSuccess) {
                    _mcpStatusMessage.value = "Installed and activated '${plugin.title}' MCP server"
                    delay(500)
                    loadMcpServers()
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Installation failed"
                    _mcpErrorMessage.value = "Failed to install ${plugin.title}: $err"
                }
            } catch (e: Exception) {
                _mcpErrorMessage.value = "Error installing ${plugin.title}: ${e.message}"
            } finally {
                _installingCascadePluginId.value = null
            }
        }
    }

    /**
     * Saves and connects a Custom MCP Server URL/port with one lightweight retry and safe fallback.
     * Never throws or blocks chat if the custom server is temporarily offline.
     */
    suspend fun connectCustomMcpServer(
        context: Context,
        authPrefs: AuthPreferences,
        rawName: String? = null,
        rawUrl: String? = null
    ): McpConnectionStatus = withContext(Dispatchers.IO) {
        val (savedName, savedUrl) = authPrefs.getCustomMcpServerSync()
        val serverName = (rawName?.trim()?.takeIf { it.isNotBlank() } ?: savedName).ifBlank { "custom-mcp" }
        val inputUrl = (rawUrl?.trim() ?: savedUrl).trim()

        if (inputUrl.isBlank()) {
            _customMcpStatus.value = McpConnectionStatus.DISCONNECTED
            _customMcpStatusDetail.value = "कोई कस्टम MCP URL सेट नहीं है — In-App व Browser MCP सक्रिय हैं"
            return@withContext McpConnectionStatus.DISCONNECTED
        }

        val normalizedUrl = AuthPreferences.normalizeServerUrl(inputUrl, 8765) ?: inputUrl
        authPrefs.saveCustomMcpServer(serverName, normalizedUrl)

        // Persist locally so config survives even when the local daemon is offline due to Android W^X
        runCatching {
            val localFile = java.io.File(context.applicationContext.filesDir, "cinecode_custom_mcp.json")
            val json = JSONObject().apply {
                put("serverName", serverName)
                put("serverUrl", normalizedUrl)
                put("updatedAt", System.currentTimeMillis())
            }
            localFile.writeText(json.toString(2))
        }

        // Also register in Antigravity mcp_config.json if IdeApiClient is reachable
        runCatching {
            val rawConfig = com.shriram.cinecode.data.daemon.IdeApiClient.getMcpConfig() ?: "{}"
            val root = if (rawConfig.trim().startsWith("{")) JSONObject(rawConfig) else JSONObject()
            val serversObj = root.optJSONObject("mcpServers") ?: JSONObject().also { root.put("mcpServers", it) }
            serversObj.put(serverName, JSONObject().apply {
                put("serverUrl", normalizedUrl)
                put("disabled", false)
            })
            com.shriram.cinecode.data.daemon.IdeApiClient.saveMcpConfig(root.toString(2))
        }

        _customMcpStatus.value = McpConnectionStatus.RETRYING
        _customMcpStatusDetail.value = "Connecting to $serverName ($normalizedUrl)…"

        val mcpService = com.shriram.cinecode.data.remote.services.AgyMcpService.instance
        var probe = mcpService.probeCustomMcpEndpoint(normalizedUrl)
        if (probe.isFailure) {
            _customMcpStatus.value = McpConnectionStatus.RETRYING
            _customMcpStatusDetail.value = "Retrying $serverName ($normalizedUrl)…"
            delay(650)
            probe = mcpService.probeCustomMcpEndpoint(normalizedUrl)
        }

        val finalStatus = if (probe.isSuccess) {
            _customMcpStatus.value = McpConnectionStatus.CONNECTED
            _mcpConnectionStatus.value = McpConnectionStatus.CONNECTED
            _customMcpStatusDetail.value = "Connected: $serverName ($normalizedUrl)"
            _mcpStatusMessage.value = "Custom MCP '$serverName' connected"
            McpConnectionStatus.CONNECTED
        } else {
            _customMcpStatus.value = McpConnectionStatus.DISCONNECTED
            val err = probe.exceptionOrNull()?.message ?: "Unreachable"
            _customMcpStatusDetail.value = "Disconnected ($err) — ऐप व चैट बिना रुके जारी हैं"
            McpConnectionStatus.DISCONNECTED
        }

        // Refresh daemon MCP list best-effort without blocking if Hub is offline
        runCatching {
            val hubUrl = AuthPreferences.currentHubUrl
            withTimeoutOrNull(2500L) {
                agyHubClient.refreshMcpServers(hubUrl)
            }
        }
        loadMcpServers()
        finalStatus
    }

    /**
     * Runs right after Sign Up / Connect: verifies In-App/Browser MCP, connects Custom MCP if configured,
     * and refreshes Hub MCP servers cleanly without blocking.
     */
    suspend fun connectAllMcpServersAfterAuth(
        context: Context,
        authPrefs: AuthPreferences,
        customServerName: String? = null,
        customServerUrl: String? = null
    ): Pair<McpConnectionStatus, String> = withContext(Dispatchers.IO) {
        com.shriram.cinecode.ui.browser.mcp.BrowserMcp.ensureReady()
        val (_, savedUrl) = authPrefs.getCustomMcpServerSync()
        val effectiveUrl = customServerUrl?.trim().takeIf { !it.isNullOrBlank() } ?: savedUrl.trim()

        val customStatus = if (effectiveUrl.isNotBlank()) {
            connectCustomMcpServer(context, authPrefs, customServerName, effectiveUrl)
        } else {
            _customMcpStatus.value = McpConnectionStatus.DISCONNECTED
            _customMcpStatusDetail.value = "In-App & Browser MCP Ready (कस्टम MCP URL वैकल्पिक)"
            McpConnectionStatus.DISCONNECTED
        }

        val hubResult = withTimeoutOrNull(3000L) {
            agyHubClient.getMcpServerStates(AuthPreferences.currentHubUrl)
        }
        if (hubResult != null && hubResult.isSuccess) {
            val liveList = hubResult.getOrDefault(emptyList())
            _mcpServers.value = liveList.sortedBy { it.name.lowercase() }
            _mcpConnectionStatus.value = McpConnectionStatus.CONNECTED
        } else if (customStatus == McpConnectionStatus.CONNECTED) {
            _mcpConnectionStatus.value = McpConnectionStatus.CONNECTED
        } else {
            // Built-in CineCodeSkillsMcp + BrowserMcp remain ready in-app even when Hub is offline
            _mcpConnectionStatus.value = McpConnectionStatus.DISCONNECTED
        }

        val summary = when {
            customStatus == McpConnectionStatus.CONNECTED ->
                "Custom MCP Connected + Browser/CineCode MCP Ready"
            _mcpConnectionStatus.value == McpConnectionStatus.CONNECTED ->
                "Antigravity Hub MCP Connected + In-App MCP Ready"
            effectiveUrl.isNotBlank() ->
                "Custom MCP Disconnected (Safe Fallback) • In-App & Browser MCP Ready"
            else ->
                "In-App & Browser MCP Ready (${com.shriram.cinecode.ui.browser.mcp.BrowserMcp.statusLabel()})"
        }
        val overallStatus = if (customStatus == McpConnectionStatus.CONNECTED || _mcpConnectionStatus.value == McpConnectionStatus.CONNECTED) {
            McpConnectionStatus.CONNECTED
        } else {
            McpConnectionStatus.DISCONNECTED
        }
        overallStatus to summary
    }

}
