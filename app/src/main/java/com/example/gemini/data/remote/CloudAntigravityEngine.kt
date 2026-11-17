package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.data.remote.RateLimit429Exception
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudAntigravityEngine(private val context: Context) {

    private val _isCloudActive = MutableStateFlow(false)
    val isCloudActive: StateFlow<Boolean> = _isCloudActive.asStateFlow()

    private val _isRecovering429 = MutableStateFlow(false)
    val isRecovering429: StateFlow<Boolean> = _isRecovering429.asStateFlow()

    private val _engineStatusMessage = MutableStateFlow("Local Bridge Mode 🔵 (:1235)")
    val engineStatusMessage: StateFlow<String> = _engineStatusMessage.asStateFlow()

    private val _currentApiKey = MutableStateFlow("")
    val currentApiKey: StateFlow<String> = _currentApiKey.asStateFlow()

    private val autorecoveryInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.code == 429) {
            _isRecovering429.value = true
            _engineStatusMessage.value = "Connecting to Fallback/Local Hub 🟠"
            CloudBridgeStore.notifyRateLimit429("CloudAntigravityEngine HTTP 429")
            Log.w("CloudAntigravityEngine", "HTTP 429 detected by interceptor — activating autorecovery")
        }
        response
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor(autorecoveryInterceptor)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    init {
        loadApiKey()
    }

    private fun loadApiKey() {
        val prefs = context.getSharedPreferences("cinecode_cloud_ai", Context.MODE_PRIVATE)
        val key = prefs.getString("google_api_key", "") ?: ""
        _currentApiKey.value = key
        _isCloudActive.value = key.isNotBlank()
        _engineStatusMessage.value = if (key.isNotBlank()) "API Active 🟢 (Cloud-Native)" else "Local Bridge Mode 🔵 (:1235)"
    }

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        CloudBridgeStore.saveApiKey(context, trimmed)
        _currentApiKey.value = trimmed
        _isCloudActive.value = trimmed.isNotBlank()
        _isRecovering429.value = false
        _engineStatusMessage.value = if (trimmed.isNotBlank()) "API Active 🟢 (Cloud-Native)" else "Local Bridge Mode 🔵 (:1235)"
        Log.i("CloudAntigravityEngine", "API Key saved. Cloud Native Mode Active: ${_isCloudActive.value}")
    }

    suspend fun chatDirect(prompt: String, modelName: String = "gemini-4-hydrogen"): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = _currentApiKey.value
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Google AI Studio API Key is not set. Falling back to Local Bridge :1235."))
        }

        val candidateModels = listOf(modelName, "gemini-2.5-flash", "gemini-2.0-flash", "gemini-3.8-flash").distinct()
        var sawRateLimit = false

        for ((index, candidate) in candidateModels.withIndex()) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidate:generateContent?key=$apiKey"
                val jsonBody = JSONObject().apply {
                    put("contents", org.json.JSONArray().put(
                        JSONObject().put("parts", org.json.JSONArray().put(
                            JSONObject().put("text", prompt)
                        ))
                    ))
                }

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    val rawBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        if (CloudBridgeStore.isRateLimitError(response.code, rawBody)) {
                            sawRateLimit = true
                            _isRecovering429.value = true
                            _engineStatusMessage.value = "Connecting to Fallback/Local Hub 🟠"
                            CloudBridgeStore.notifyRateLimit429(candidate)
                            return@use
                        }
                        return@withContext Result.failure(RuntimeException("Cloud API Error (${response.code}): $rawBody"))
                    }

                    val json = JSONObject(rawBody.ifBlank { "{}" })
                    val candidates = json.optJSONArray("candidates")
                    val text = if (candidates != null && candidates.length() > 0) {
                        candidates.getJSONObject(0)
                            .optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.getJSONObject(0)
                            ?.optString("text", "No response text") ?: "No content"
                    } else {
                        "No output candidates"
                    }

                    _isCloudActive.value = true
                    if (index == 0) {
                        _isRecovering429.value = false
                        _engineStatusMessage.value = "API Active 🟢 (Cloud-Native)"
                        CloudBridgeStore.clearRecoveryState()
                        return@withContext Result.success("[API Active 🟢 (Cloud-Native)]\n$text")
                    } else {
                        _isRecovering429.value = true
                        _engineStatusMessage.value = "Free Fallback Cloud Engine 🟠 ($candidate)"
                        return@withContext Result.success("[Free Fallback Cloud Engine 🟠 · $candidate]\n$text")
                    }
                }
            } catch (e: Exception) {
                Log.e("CloudAntigravityEngine", "Direct cloud call failed on $candidate", e)
                if (!sawRateLimit) {
                    return@withContext Result.failure(e)
                }
            }
        }

        if (sawRateLimit) {
            _isRecovering429.value = true
            _engineStatusMessage.value = "Connecting to Fallback/Local Hub 🟠"
            return@withContext Result.failure(
                RateLimit429Exception("HTTP 429 RESOURCE_EXHAUSTED — Connecting to Fallback/Local Hub 🟠")
            )
        }
        Result.failure(RuntimeException("Cloud API unreachable"))
    }
}
