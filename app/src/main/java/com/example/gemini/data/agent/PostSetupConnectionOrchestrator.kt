package com.shriram.cinecode.data.agent

import android.content.Context
import android.util.Log
import com.shriram.cinecode.data.agent.agy.AgyChatBackend
import com.shriram.cinecode.data.agent.agy.AgyMcpManager
import com.shriram.cinecode.data.agent.agy.AgyPluginsManager
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import com.shriram.cinecode.data.local.LocalServerManager
import com.shriram.cinecode.data.preferences.AuthPreferences
import com.shriram.cinecode.data.remote.AgyBridgeService
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.data.remote.services.AgyAuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class PostSetupStepStatus {
    IDLE,
    RUNNING,
    CONNECTED,
    FALLBACK,
    DISCONNECTED
}

data class PostSetupPipelineState(
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val googleAgyStatus: PostSetupStepStatus = PostSetupStepStatus.IDLE,
    val googleAgyDetail: String = "Google व Antigravity सेशन के लिए तैयार",
    val mcpStatus: PostSetupStepStatus = PostSetupStepStatus.IDLE,
    val mcpDetail: String = "Custom MCP व Browser MCP प्रतीक्षारत",
    val pluginsStatus: PostSetupStepStatus = PostSetupStepStatus.IDLE,
    val pluginsDetail: String = "सभी प्लगइन्स और Mini Apps प्रतीक्षारत"
)

/**
 * Coordinates the post-setup Sign Up / Connect sequence cleanly and sequentially for 4GB RAM devices:
 * 1) Save free profile / API Key & connect Google / Antigravity (with safe W^X Cloud fallback)
 * 2) Save & connect Custom MCP Server + Browser/In-App MCP
 * 3) Sequentially initialize all Plugins, Mini Apps, and Skills
 */
object PostSetupConnectionOrchestrator {
    private const val TAG = "PostSetupOrchestrator"
    private val runMutex = Mutex()

    private val _state = MutableStateFlow(PostSetupPipelineState())
    val state: StateFlow<PostSetupPipelineState> = _state.asStateFlow()

    suspend fun runFullConnectSequence(
        context: Context,
        authPrefs: AuthPreferences,
        agyBackend: AgyChatBackend,
        mcpManager: AgyMcpManager,
        pluginsManager: AgyPluginsManager,
        profileName: String = "",
        profileEmail: String = "",
        apiKey: String = "",
        customMcpName: String = "",
        customMcpUrl: String = ""
    ): PostSetupPipelineState = runMutex.withLock {
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            CloudBridgeStore.ensureInitialized(appCtx)

            // Save optional free profile & API key immediately
            if (profileName.isNotBlank() || profileEmail.isNotBlank()) {
                authPrefs.savePostSetupProfile(profileName, profileEmail)
            }
            if (apiKey.isNotBlank()) {
                CloudBridgeStore.saveApiKey(appCtx, apiKey)
            }

            _state.value = PostSetupPipelineState(
                isRunning = true,
                isCompleted = false,
                googleAgyStatus = PostSetupStepStatus.RUNNING,
                googleAgyDetail = "Google / Antigravity ब्रिज और सेशन की जांच हो रही है…",
                mcpStatus = PostSetupStepStatus.IDLE,
                mcpDetail = "कस्टम MCP कनेक्शन प्रतीक्षारत…",
                pluginsStatus = PostSetupStepStatus.IDLE,
                pluginsDetail = "सभी प्लगइन्स क्रमिक लोड के लिए प्रतीक्षारत…"
            )

            // ==================== STEP 1: GOOGLE & ANTIGRAVITY ====================
            val isWxRestricted = LocalEnvironmentManager.isLocalExecRestricted(appCtx)
            val hasCloudKey = CloudBridgeStore.hasApiKey(appCtx)
            var isHubReachable = false

            runCatching {
                // Only attempt starting local bridge script when not W^X restricted and not remote mode
                if (!isWxRestricted && !AuthPreferences.currentRemoteMode && LocalServerManager.hasServerScript(appCtx)) {
                    LocalServerManager.startServer(appCtx, forceRestart = false)
                }
                val bridgeUp = AgyBridgeService.instance.probeBridgeFast(AuthPreferences.currentBridgeHttpUrl)
                if (bridgeUp) {
                    agyBackend.checkBridgeHealth()
                    val sessionRes = AgyAuthService.instance.verifyOrConnectSession(
                        hubUrl = AuthPreferences.currentHubUrl,
                        timeoutMs = 3800L
                    )
                    if (sessionRes.isSuccess) {
                        isHubReachable = true
                        agyBackend.checkAgyAuthStatus(userInitiated = false)
                        val info = sessionRes.getOrThrow()
                        if (info.isLoggedIn) {
                            _state.value = _state.value.copy(
                                googleAgyStatus = PostSetupStepStatus.CONNECTED,
                                googleAgyDetail = "Antigravity & Google Connected (${info.displayName.ifBlank { info.email.ifBlank { "Active" } }})"
                            )
                        } else {
                            _state.value = _state.value.copy(
                                googleAgyStatus = if (hasCloudKey) PostSetupStepStatus.CONNECTED else PostSetupStepStatus.FALLBACK,
                                googleAgyDetail = if (hasCloudKey) {
                                    "Antigravity Hub Online + API Key Connected"
                                } else {
                                    "Antigravity Hub Online — आप Google Sign-In या API Key से तुरंत चैट कर सकते हैं"
                                }
                            )
                        }
                    } else {
                        _state.value = _state.value.copy(
                            googleAgyStatus = if (hasCloudKey) PostSetupStepStatus.CONNECTED else PostSetupStepStatus.FALLBACK,
                            googleAgyDetail = if (hasCloudKey) {
                                "Bridge Online • Cloud API Active (Connected)"
                            } else {
                                "Bridge Online • In-App / Cloud Mode Ready"
                            }
                        )
                    }
                } else {
                    // Graceful Android W^X (error=13) / offline local bridge fallback — never crash or hang
                    val detail = when {
                        hasCloudKey && isWxRestricted ->
                            "API Active (Connected) • Android W^X Cloud Fallback सक्रिय"
                        hasCloudKey ->
                            "Google AI Studio API Active (Connected) • Cloud मोड सक्रिय"
                        isWxRestricted ->
                            "In-App / Cloud Fallback सक्रिय (Android W^X पर सुरक्षित)"
                        else ->
                            "In-App & Cloud Mode Ready (लोकल ब्रिज ऑफलाइन होने पर सुरक्षित फ़ॉलबैक)"
                    }
                    _state.value = _state.value.copy(
                        googleAgyStatus = if (hasCloudKey) PostSetupStepStatus.CONNECTED else PostSetupStepStatus.FALLBACK,
                        googleAgyDetail = detail
                    )
                }
            }.onFailure { err ->
                Log.w(TAG, "Step 1 fallback: ${err.message}")
                _state.value = _state.value.copy(
                    googleAgyStatus = PostSetupStepStatus.FALLBACK,
                    googleAgyDetail = if (hasCloudKey) {
                        "Google API Active (Connected) • Safe Fallback"
                    } else {
                        "In-App / Cloud Fallback Ready"
                    }
                )
            }

            delay(140)

            // ==================== STEP 2: CUSTOM MCP & BROWSER MCP ====================
            _state.value = _state.value.copy(
                mcpStatus = PostSetupStepStatus.RUNNING,
                mcpDetail = "Custom MCP और In-App MCP सर्वर कनेक्ट हो रहे हैं…"
            )

            runCatching {
                val (mcpStatus, mcpSummary) = mcpManager.connectAllMcpServersAfterAuth(
                    context = appCtx,
                    authPrefs = authPrefs,
                    customServerName = customMcpName,
                    customServerUrl = customMcpUrl
                )
                val mappedStatus = when {
                    mcpStatus == AgyMcpManager.McpConnectionStatus.CONNECTED -> PostSetupStepStatus.CONNECTED
                    customMcpUrl.isBlank() && authPrefs.getCustomMcpServerSync().second.isBlank() -> PostSetupStepStatus.FALLBACK
                    else -> PostSetupStepStatus.DISCONNECTED
                }
                _state.value = _state.value.copy(
                    mcpStatus = mappedStatus,
                    mcpDetail = mcpSummary
                )
            }.onFailure { err ->
                Log.w(TAG, "Step 2 MCP fallback: ${err.message}")
                _state.value = _state.value.copy(
                    mcpStatus = PostSetupStepStatus.FALLBACK,
                    mcpDetail = "In-App & Browser MCP Ready (Safe Fallback)"
                )
            }

            delay(140)

            // ==================== STEP 3: ALL PLUGINS & MINI APPS (SEQUENTIAL) ====================
            _state.value = _state.value.copy(
                pluginsStatus = PostSetupStepStatus.RUNNING,
                pluginsDetail = "सभी प्लगइन्स व Mini Apps क्रमिक रूप से लोड हो रहे हैं…"
            )

            runCatching {
                val summary = pluginsManager.initializeAllPluginsSequentially(
                    context = appCtx,
                    isHubReachable = isHubReachable
                )
                _state.value = _state.value.copy(
                    pluginsStatus = PostSetupStepStatus.CONNECTED,
                    pluginsDetail = summary.statusMessage
                )
            }.onFailure { err ->
                Log.w(TAG, "Step 3 Plugins fallback: ${err.message}")
                _state.value = _state.value.copy(
                    pluginsStatus = PostSetupStepStatus.FALLBACK,
                    pluginsDetail = "Built-in Creator Plugins & Skills Ready"
                )
            }

            authPrefs.savePostSetupConnectCompleted(true)
            val finalState = _state.value.copy(
                isRunning = false,
                isCompleted = true
            )
            _state.value = finalState
            finalState
        }
    }

    /**
     * Lightweight background initialization used when the user taps "Skip / Use API Key"
     * or returns from OAuth sign-in so Plugins and MCP still activate cleanly without blocking.
     */
    suspend fun runLightweightBackgroundConnect(
        context: Context,
        authPrefs: AuthPreferences,
        agyBackend: AgyChatBackend,
        mcpManager: AgyMcpManager,
        pluginsManager: AgyPluginsManager,
        apiKey: String = ""
    ) {
        runFullConnectSequence(
            context = context,
            authPrefs = authPrefs,
            agyBackend = agyBackend,
            mcpManager = mcpManager,
            pluginsManager = pluginsManager,
            apiKey = apiKey
        )
    }
}
