package com.example.gemini.data.remote

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudAntigravityEngine(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _isCloudActive = MutableStateFlow(false)
    val isCloudActive: StateFlow<Boolean> = _isCloudActive.asStateFlow()

    private val _currentApiKey = MutableStateFlow("")
    val currentApiKey: StateFlow<String> = _currentApiKey.asStateFlow()

    init {
        loadApiKey()
    }

    private fun loadApiKey() {
        val prefs = context.getSharedPreferences("cinecode_cloud_ai", Context.MODE_PRIVATE)
        val key = prefs.getString("google_api_key", "") ?: ""
        _currentApiKey.value = key
        _isCloudActive.value = key.isNotBlank()
    }

    fun saveApiKey(key: String) {
        val trimmed = key.trim()
        val prefs = context.getSharedPreferences("cinecode_cloud_ai", Context.MODE_PRIVATE)
        prefs.edit().putString("google_api_key", trimmed).apply()
        _currentApiKey.value = trimmed
        _isCloudActive.value = trimmed.isNotBlank()
        Log.i("CloudAntigravityEngine", "API Key saved. Cloud Native Mode Active: ${_isCloudActive.value}")
    }

    suspend fun chatDirect(prompt: String, modelName: String = "gemini-4-hydrogen"): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = _currentApiKey.value
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Google AI Studio API Key is not set. Please configure in Settings."))
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
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
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: "HTTP ${response.code}"
                    return@withContext Result.failure(RuntimeException("Cloud API Error: $err"))
                }

                val bodyStr = response.body?.string() ?: "{}"
                val json = JSONObject(bodyStr)
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
                Result.success("[API Active 🟢 (Cloud-Native)]\n$text")
            }
        } catch (e: Exception) {
            Log.e("CloudAntigravityEngine", "Direct cloud call failed", e)
            Result.failure(e)
        }
    }
}
