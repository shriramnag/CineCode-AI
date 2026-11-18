package com.shriram.cinecode.data.agent.opencode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OpenCodePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun setBaseUrl(url: String) {
        val clean = url.trim().ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        prefs.edit().putString(KEY_BASE_URL, clean).apply()
        _baseUrl.value = clean
    }

    fun setApiKey(key: String) {
        val clean = key.trim()
        prefs.edit().putString(KEY_API_KEY, clean).apply()
        _apiKey.value = clean
    }

    fun setSelectedModel(modelId: String) {
        val clean = modelId.trim().ifBlank { DEFAULT_MODEL }
        prefs.edit().putString(KEY_MODEL, clean).apply()
        _selectedModel.value = clean
    }

    companion object {
        private const val PREFS = "cinecode_opencode_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "selected_model"

        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "qwen/qwen3-coder:free"
    }
}
