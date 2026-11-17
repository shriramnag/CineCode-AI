package com.shriram.cinecode.data.remote

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.NeuralMemoryVault
import com.shriram.cinecode.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class RateLimit429Exception(message: String) : RuntimeException(message)

data class EngineDiagnosticsReport(
    val cloudConfigured: Boolean,
    val cloudReachable: Boolean,
    val cloudStatusLabel: String,
    val isRateLimit429: Boolean,
    val localHubOnline: Boolean,
    val bridgePortOnline: Boolean,
    val localStatusLabel: String,
    val guidance: String
)

/**
 * Unified Dual-Bridge store & Cloud-Native Gemini REST client.
 * Synchronizes API Key across SharedPreferences ("cinecode_cloud_ai") and DataStore (NeuralMemoryVault)
 * with non-blocking HTTP 429 Autorecovery and isolated Dual-Engine Diagnostics.
 */
object CloudBridgeStore {
    private const val TAG = "CloudBridgeStore"
    private const val PREFS_NAME = "cinecode_cloud_ai"
    private const val KEY_API_KEY = "google_api_key"
    private const val KEY_SEARCH_ENABLED = "google_search_enabled"
    private const val KEY_LIVE_VOICE_ENABLED = "live_voice_enabled"
    private const val KEY_CLOUD_MODEL = "cloud_model"

    const val MODEL_HYDROGEN = "gemini-4-hydrogen"
    const val MODEL_CARBON = "gemini-4-carbon"
    const val MODEL_FLASH = "gemini-3.8-flash"

    private val FREE_FALLBACK_CLOUD_MODELS = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        MODEL_FLASH
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var initialized = false

    private val _apiKey = MutableStateFlow("")
    val apiKeyFlow: StateFlow<String> = _apiKey.asStateFlow()

    private val _isCloudActive = MutableStateFlow(false)
    val isCloudActiveFlow: StateFlow<Boolean> = _isCloudActive.asStateFlow()

    private val _searchGroundingEnabled = MutableStateFlow(true)
    val searchGroundingEnabledFlow: StateFlow<Boolean> = _searchGroundingEnabled.asStateFlow()

    private val _liveVoiceEnabled = MutableStateFlow(true)
    val liveVoiceEnabledFlow: StateFlow<Boolean> = _liveVoiceEnabled.asStateFlow()

    private val _selectedModel = MutableStateFlow(MODEL_HYDROGEN)
    val selectedModelFlow: StateFlow<String> = _selectedModel.asStateFlow()

    private val _isRateLimitRecovering = MutableStateFlow(false)
    val isRateLimitRecoveringFlow: StateFlow<Boolean> = _isRateLimitRecovering.asStateFlow()

    private val _activeEngineMode = MutableStateFlow("Auto Dual-Engine")
    val activeEngineModeFlow: StateFlow<String> = _activeEngineMode.asStateFlow()

    private val _lastRecoveryNotice = MutableStateFlow<String?>(null)
    val lastRecoveryNoticeFlow: StateFlow<String?> = _lastRecoveryNotice.asStateFlow()

    private val _cloudKeyVerified = MutableStateFlow<Boolean?>(null)
    val cloudKeyVerifiedFlow: StateFlow<Boolean?> = _cloudKeyVerified.asStateFlow()

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
            _apiKey.value = savedKey
            _isCloudActive.value = savedKey.isNotBlank()
            _isRateLimitRecovering.value = false
            _lastRecoveryNotice.value = null
            _searchGroundingEnabled.value = prefs.getBoolean(KEY_SEARCH_ENABLED, true)
            _liveVoiceEnabled.value = prefs.getBoolean(KEY_LIVE_VOICE_ENABLED, true)
            _selectedModel.value = prefs.getString(KEY_CLOUD_MODEL, MODEL_HYDROGEN) ?: MODEL_HYDROGEN
            _activeEngineMode.value = if (savedKey.isNotBlank()) "API Active 🟢 (Connected)" else "Local Bridge (:1235) 🔵"
            initialized = true

            // Also hydrate from DataStore if SharedPreferences was empty
            if (savedKey.isBlank()) {
                scope.launch {
                    runCatching {
                        val vault = NeuralMemoryVault(appCtx)
                        val dsKey = vault.apiKeyFlow.first().trim()
                        if (dsKey.isNotBlank() && _apiKey.value.isBlank()) {
                            prefs.edit().putString(KEY_API_KEY, dsKey).apply()
                            _apiKey.value = dsKey
                            _isCloudActive.value = true
                            _isRateLimitRecovering.value = false
                            _lastRecoveryNotice.value = null
                            _activeEngineMode.value = "API Active 🟢 (Connected)"
                            verifyApiKeyLiveInternal(dsKey)
                        }
                    }
                }
            } else {
                scope.launch { verifyApiKeyLiveInternal(savedKey) }
            }
        }
    }

    fun hasApiKey(context: Context): Boolean {
        ensureInitialized(context)
        return _apiKey.value.isNotBlank() && _isCloudActive.value
    }

    fun notifyRateLimit429(source: String = "Cloud API") {
        _isRateLimitRecovering.value = true
        _activeEngineMode.value = "Connecting to Fallback/Local Hub 🟠"
        _lastRecoveryNotice.value = "Connecting to Fallback/Local Hub 🟠 ($source 429 Quota)"
    }

    fun clearRecoveryState() {
        _isRateLimitRecovering.value = false
        _lastRecoveryNotice.value = null
        val keyPresent = _apiKey.value.isNotBlank()
        _isCloudActive.value = keyPresent
        _activeEngineMode.value = if (keyPresent) "API Active 🟢 (Connected)" else "Local Bridge (:1235) 🔵"
    }

    private fun verifyApiKeyLiveInternal(apiKey: String) {
        if (apiKey.isBlank()) {
            _cloudKeyVerified.value = null
            return
        }
        runCatching {
            val probeUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val req = Request.Builder().url(probeUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val errBody = if (!resp.isSuccessful) resp.body?.string().orEmpty() else ""
                when {
                    code in 200..299 -> {
                        _cloudKeyVerified.value = true
                        _isCloudActive.value = true
                        _isRateLimitRecovering.value = false
                        _lastRecoveryNotice.value = null
                        _activeEngineMode.value = "API Active 🟢 (Connected)"
                    }
                    isRateLimitError(code, errBody) -> {
                        // Key is valid even if quota is throttled; keep Cloud active & switch gracefully
                        _cloudKeyVerified.value = true
                        _isCloudActive.value = true
                    }
                    code == 400 || code == 401 || code == 403 -> {
                        _cloudKeyVerified.value = false
                    }
                }
            }
        }
    }

    fun saveApiKey(context: Context, key: String) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val trimmed = key.trim()
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, trimmed)
            .apply()
        _apiKey.value = trimmed
        _isCloudActive.value = trimmed.isNotBlank()
        _cloudKeyVerified.value = if (trimmed.isNotBlank()) true else null
        clearRecoveryState()

        if (trimmed.isNotBlank()) {
            scope.launch { verifyApiKeyLiveInternal(trimmed) }
        }

        scope.launch {
            runCatching {
                val vault = NeuralMemoryVault(appCtx)
                val lang = vault.languageFlow.first()
                val lock = vault.codeLockFlow.first()
                val brand = vault.brandingFlow.first()
                vault.savePreferences(lang, lock, brand, trimmed)
            }.onFailure {
                Log.w(TAG, "Failed syncing API key to DataStore: ${it.message}")
            }
        }
    }

    fun clearApiKey(context: Context) {
        saveApiKey(context, "")
    }

    fun setSearchGroundingEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEARCH_ENABLED, enabled)
            .apply()
        _searchGroundingEnabled.value = enabled
    }

    fun setLiveVoiceEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LIVE_VOICE_ENABLED, enabled)
            .apply()
        _liveVoiceEnabled.value = enabled
    }

    fun setSelectedModel(context: Context, model: String) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLOUD_MODEL, model)
            .apply()
        _selectedModel.value = model
    }

    fun isRateLimitError(httpCode: Int, body: String): Boolean {
        return httpCode == 429 ||
            body.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            body.contains("rate limit", ignoreCase = true) ||
            body.contains("quota", ignoreCase = true)
    }

    suspend fun chatDirect(
        context: Context,
        prompt: String,
        modelName: String = _selectedModel.value,
        useSearchGrounding: Boolean = _searchGroundingEnabled.value
    ): Result<String> = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val apiKey = _apiKey.value
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Google AI Studio API Key is empty. Falling back to Local Bridge :1235.")
            )
        }

        val modelsToTry = (listOf(modelName) + FREE_FALLBACK_CLOUD_MODELS).distinct()
        var hitRateLimit429 = false
        var lastErrorMsg = "Cloud API error"

        for ((index, candidateModel) in modelsToTry.withIndex()) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"
                val body = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                    if (useSearchGrounding) {
                        put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
                    }
                }

                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .header("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (isRateLimitError(response.code, raw)) {
                            hitRateLimit429 = true
                            lastErrorMsg = "HTTP ${response.code} RESOURCE_EXHAUSTED"
                            notifyRateLimit429(candidateModel)
                            Log.w(TAG, "API 429 hit on $candidateModel — switching to Free Fallback Cloud Engine")
                            return@use
                        }
                        return@withContext Result.failure(
                            RuntimeException("Cloud API HTTP ${response.code}: $raw")
                        )
                    }

                    val json = JSONObject(raw.ifBlank { "{}" })
                    val candidates = json.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                    val textBuilder = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                            if (partText.isNotEmpty()) {
                                if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                                textBuilder.append(partText)
                            }
                        }
                    }
                    val finalText = textBuilder.toString().ifBlank { "No response text returned." }
                    if (index == 0) {
                        clearRecoveryState()
                        return@withContext Result.success(finalText)
                    } else {
                        _isRateLimitRecovering.value = true
                        _activeEngineMode.value = "Free Fallback Cloud Engine 🟠 ($candidateModel)"
                        _lastRecoveryNotice.value = "429 Autorecovery: Free Fallback Cloud Engine ($candidateModel) सक्रिय है 🟠"
                        return@withContext Result.success("[Free Fallback Cloud Engine 🟠 · $candidateModel]\n$finalText")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct cloud chat failed on $candidateModel", e)
                lastErrorMsg = e.message ?: "Network error"
                if (!hitRateLimit429) {
                    return@withContext Result.failure(e)
                }
            }
        }

        if (hitRateLimit429) {
            notifyRateLimit429("Quota 429")
            return@withContext Result.failure(
                RateLimit429Exception("HTTP 429 RESOURCE_EXHAUSTED — Connecting to Fallback/Local Hub 🟠")
            )
        }
        Result.failure(RuntimeException(lastErrorMsg))
    }

    fun isLocalPortOpen(port: Int, timeoutMs: Int = 1100): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun isBridgeHttpHealthy(): Boolean {
        val bridgeUrl = com.shriram.cinecode.data.preferences.AuthPreferences.currentBridgeHttpUrl.trimEnd('/')
        return runCatching {
            val req = Request.Builder().url("$bridgeUrl/api/health").get().build()
            httpClient.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
                .newCall(req)
                .execute()
                .use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun runEngineDiagnostics(context: Context): EngineDiagnosticsReport = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInitialized(appCtx)
        val apiKey = _apiKey.value

        var localHubOnline = isLocalPortOpen(BuildConfig.HUB_PORT)
        var bridgePortOnline = isLocalPortOpen(BuildConfig.BRIDGE_PORT) || isBridgeHttpHealthy()

        // Attempt auto-start/reconnect for local bridge when local tools are already installed
        if (!bridgePortOnline && com.shriram.cinecode.data.local.LocalEnvironmentManager.isInstalled(appCtx)) {
            runCatching {
                com.shriram.cinecode.data.local.LocalEnvironmentManager.ensureExecutablesPermissions(appCtx)
                com.shriram.cinecode.data.local.LocalServerManager.startServer(appCtx, forceRestart = false)
            }
            kotlinx.coroutines.delay(500)
            localHubOnline = isLocalPortOpen(BuildConfig.HUB_PORT)
            bridgePortOnline = isLocalPortOpen(BuildConfig.BRIDGE_PORT) || isBridgeHttpHealthy()
        }
        if (bridgePortOnline && !AgyBridgeService.instance.systemConnectionState.value.isBridgeOnline) {
            runCatching {
                AgyBridgeService.instance.fetchServerStatus()?.let { status ->
                    AgyBridgeService.instance.updateHubStatus(status)
                }
            }
        }

        var cloudReachable = false
        var is429 = false
        val cloudLabel: String

        if (apiKey.isBlank()) {
            _isCloudActive.value = false
            _cloudKeyVerified.value = null
            cloudLabel = "Not Configured (Local Bridge :${BuildConfig.HUB_PORT} Primary)"
        } else {
            val probeResult = runCatching {
                val probeUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                val req = Request.Builder().url(probeUrl).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val errText = if (!resp.isSuccessful) resp.body?.string().orEmpty() else ""
                    Pair(code, errText)
                }
            }
            val codeAndBody = probeResult.getOrNull()
            if (codeAndBody == null) {
                cloudLabel = "Cloud Unreachable (Network Error — Local Hub Isolated)"
            } else {
                val (code, errText) = codeAndBody
                when {
                    code in 200..299 -> {
                        cloudReachable = true
                        _cloudKeyVerified.value = true
                        _isCloudActive.value = true
                        clearRecoveryState()
                        cloudLabel = "API Active 🟢 (Connected · HTTP $code OK · ${_selectedModel.value})"
                    }
                    isRateLimitError(code, errText) -> {
                        is429 = true
                        _cloudKeyVerified.value = true
                        _isCloudActive.value = true
                        notifyRateLimit429("Diagnostics")
                        cloudLabel = "HTTP 429 Rate Limit 🟠 (Autorecovery & Fallback Active)"
                    }
                    else -> {
                        _cloudKeyVerified.value = false
                        cloudLabel = "Cloud API HTTP $code (Check Key — Local Hub Isolated)"
                    }
                }
            }
        }

        val localLabel = when {
            localHubOnline && bridgePortOnline -> "Online 🟢 (Hub :${BuildConfig.HUB_PORT} & Bridge :${BuildConfig.BRIDGE_PORT} Active)"
            localHubOnline -> "Hub Online 🟢 (:${BuildConfig.HUB_PORT} Ready)"
            bridgePortOnline -> "Bridge Online 🟢 (:${BuildConfig.BRIDGE_PORT} Ready)"
            else -> "Standby / Offline 🔵 (Isolated from Cloud Errors)"
        }

        val guidance = when {
            is429 && localHubOnline -> "✅ API 429 कोटा सीमा मिली, पर Local Hub :${BuildConfig.HUB_PORT} ऑनलाइन है — बिना रुकावट काम जारी रहेगा।"
            is429 -> "🟠 API 429 कोटा मिला: Free Fallback Cloud Engine सक्रिय है। पूर्ण ऑफलाइन गति के लिए Termux / Local Hub (:${BuildConfig.HUB_PORT}) चालू कर सकते हैं।"
            cloudReachable && localHubOnline -> "✅ दोनों इंजन (API Active 🟢 + Local Hub :${BuildConfig.HUB_PORT} 🟢) पूरी तरह कनेक्टेड और स्वस्थ हैं।"
            cloudReachable -> "✅ Cloud Native Engine सक्रिय और कनेक्टेड है। Local Server (:${BuildConfig.HUB_PORT}) स्वतंत्र रूप से स्टैंडबाय पर है।"
            localHubOnline || bridgePortOnline -> "✅ Local Antigravity Engine सक्रिय है। क्लाउड के बिना भी कोडिंग चलेगी।"
            else -> "ℹ️ Cloud API Key डालें या Local Termux Server (:${BuildConfig.HUB_PORT}) शुरू करें।"
        }

        EngineDiagnosticsReport(
            cloudConfigured = apiKey.isNotBlank(),
            cloudReachable = cloudReachable,
            cloudStatusLabel = cloudLabel,
            isRateLimit429 = is429,
            localHubOnline = localHubOnline,
            bridgePortOnline = bridgePortOnline,
            localStatusLabel = localLabel,
            guidance = guidance
        )
    }
}
