package com.shriram.cinecode.data.agent.codex

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CodexProviderPreset(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val requiresKey: Boolean
)

class CodexPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _reasoningEffort = MutableStateFlow(prefs.getString(KEY_EFFORT, "medium") ?: "medium")
    val reasoningEffort: StateFlow<String> = _reasoningEffort.asStateFlow()

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

    fun setReasoningEffort(effort: String) {
        prefs.edit().putString(KEY_EFFORT, effort).apply()
        _reasoningEffort.value = effort
    }

    companion object {
        private const val PREFS = "cinecode_codex_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_EFFORT = "reasoning_effort"

        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_MODEL = "qwen/qwen3-coder:free"

        val PRESETS = listOf(
            CodexProviderPreset("openrouter_free", "OpenRouter (:free Tier)", "https://openrouter.ai/api/v1", "qwen/qwen3-coder:free", true),
            CodexProviderPreset("groq_free", "Groq Free Fast Inference", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", true),
            CodexProviderPreset("ollama_local", "Ollama Local (No Key)", "http://127.0.0.1:11434/v1", "qwen2.5-coder:3b", false),
            CodexProviderPreset("gemini_openai", "Google AI Studio (Free)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", true)
        )
    }
}
