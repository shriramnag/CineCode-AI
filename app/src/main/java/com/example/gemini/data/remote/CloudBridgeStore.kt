package com.shriram.cinecode.data.remote

import android.content.Context
import android.util.Log
import com.example.gemini.data.preferences.NeuralMemoryVault
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
import java.util.concurrent.TimeUnit

/**
 * Unified Dual-Bridge store & Cloud-Native Gemini REST client.
 * Synchronizes API Key across SharedPreferences ("cinecode_cloud_ai") and DataStore (NeuralMemoryVault)
 * so CloudAntigravityEngine, GeminiSearchGroundingClient, GeminiLiveVoiceClient, and AgyChatBackend
 * stay in sync without touching existing local :1235 bridge logic.
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

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
            _apiKey.value = savedKey
            _isCloudActive.value = savedKey.isNotBlank()
            _searchGroundingEnabled.value = prefs.getBoolean(KEY_SEARCH_ENABLED, true)
            _liveVoiceEnabled.value = prefs.getBoolean(KEY_LIVE_VOICE_ENABLED, true)
            _selectedModel.value = prefs.getString(KEY_CLOUD_MODEL, MODEL_HYDROGEN) ?: MODEL_HYDROGEN
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
                        }
                    }
                }
            }
        }
    }

    fun hasApiKey(context: Context): Boolean {
        ensureInitialized(context)
        return _isCloudActive.value
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

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
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
                Result.success(finalText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct cloud chat failed", e)
            Result.failure(e)
        }
    }
}
