package com.example.gemini.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.neuralMemoryDataStore by preferencesDataStore(name = "neural_memory_vault")

class NeuralMemoryVault(private val context: Context) {
    companion object {
        private val PREF_LANGUAGE = stringPreferencesKey("preferred_language")
        private val PREF_CODE_LOCK = stringPreferencesKey("working_code_lock")
        private val PREF_BRANDING = stringPreferencesKey("ai_branding")
        private val PREF_AI_KEY = stringPreferencesKey("ai_studio_api_key")
    }

    val languageFlow: Flow<String> = context.neuralMemoryDataStore.data.map { prefs ->
        prefs[PREF_LANGUAGE] ?: "Hindi (शुद्ध हिंदी & हिंग्लिश)"
    }

    val codeLockFlow: Flow<Boolean> = context.neuralMemoryDataStore.data.map { prefs ->
        (prefs[PREF_CODE_LOCK] ?: "true").toBoolean()
    }

    val brandingFlow: Flow<String> = context.neuralMemoryDataStore.data.map { prefs ->
        prefs[PREF_BRANDING] ?: "Shiv AI human realistic"
    }

    val apiKeyFlow: Flow<String> = context.neuralMemoryDataStore.data.map { prefs ->
        prefs[PREF_AI_KEY] ?: ""
    }

    suspend fun savePreferences(language: String, codeLock: Boolean, branding: String, apiKey: String) {
        context.neuralMemoryDataStore.edit { prefs ->
            prefs[PREF_LANGUAGE] = language
            prefs[PREF_CODE_LOCK] = codeLock.toString()
            prefs[PREF_BRANDING] = branding
            prefs[PREF_AI_KEY] = apiKey
        }
    }
}
