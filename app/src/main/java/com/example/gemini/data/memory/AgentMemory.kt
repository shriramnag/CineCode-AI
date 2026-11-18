package com.shriram.cinecode.data.memory

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class AgentMemoryRule(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val instruction: String,
    val category: String = "Preference", // Preference, Style, Safety, Language
    val enabled: Boolean = true,
    val locked: Boolean = false
)

/**
 * Persistent Agent Memory for CineCode-AI.
 * Remembers user preferences (Hindi language, 100% free tools, @MahaPauranik script style, 4GB RAM safe mode)
 * across all coding agents without consuming unnecessary memory.
 */
object AgentMemory {
    private const val PREFS_NAME = "cinecode_agent_memory"
    private const val KEY_RULES_JSON = "memory_rules_json"
    private const val KEY_SEEDED = "memory_seeded_v1"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile
    private var prefs: SharedPreferences? = null

    private val _rules = MutableStateFlow<List<AgentMemoryRule>>(defaultRules())
    val rules: StateFlow<List<AgentMemoryRule>> = _rules.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        if (!p.getBoolean(KEY_SEEDED, false)) {
            val defaults = defaultRules()
            p.edit()
                .putString(KEY_RULES_JSON, json.encodeToString(defaults))
                .putBoolean(KEY_SEEDED, true)
                .apply()
            _rules.value = defaults
        } else {
            val raw = p.getString(KEY_RULES_JSON, null)
            val loaded = if (!raw.isNullOrBlank()) {
                runCatching { json.decodeFromString<List<AgentMemoryRule>>(raw) }.getOrNull()
            } else null
            _rules.value = if (!loaded.isNullOrEmpty()) loaded else defaultRules()
        }
    }

    fun addRule(title: String, instruction: String, category: String = "Custom") {
        val cleanTitle = title.trim()
        val cleanInstruction = instruction.trim()
        if (cleanTitle.isBlank() || cleanInstruction.isBlank()) return
        val updated = _rules.value + AgentMemoryRule(
            title = cleanTitle,
            instruction = cleanInstruction,
            category = category,
            enabled = true,
            locked = false
        )
        persist(updated)
    }

    fun toggleRule(id: String) {
        val updated = _rules.value.map { rule ->
            if (rule.id == id) rule.copy(enabled = !rule.enabled) else rule
        }
        persist(updated)
    }

    fun deleteRule(id: String) {
        val updated = _rules.value.filterNot { it.id == id && !it.locked }
        persist(updated)
    }

    fun updateRule(id: String, title: String, instruction: String) {
        val updated = _rules.value.map { rule ->
            if (rule.id == id) rule.copy(title = title.trim(), instruction = instruction.trim()) else rule
        }
        persist(updated)
    }

    fun resetDefaults() {
        persist(defaultRules())
    }

    fun buildPromptMemoryBlock(): String {
        val active = _rules.value.filter { it.enabled }
        if (active.isEmpty()) return ""
        return buildString {
            append("[CineCode-AI Persistent Memory]\n")
            active.forEach { rule ->
                append("- ").append(rule.title).append(": ").append(rule.instruction).append('\n')
            }
        }.trim()
    }

    private fun persist(list: List<AgentMemoryRule>) {
        _rules.value = list
        prefs?.edit()?.putString(KEY_RULES_JSON, json.encodeToString(list))?.apply()
    }

    private fun defaultRules(): List<AgentMemoryRule> = listOf(
        AgentMemoryRule(
            id = "mem_hindi_pref",
            title = "हिंदी भाषा प्राथमिकता (Hindi Language)",
            instruction = "Communicate explanations and summaries clearly in simple Hindi (or Hinglish) unless the user requests English.",
            category = "Language",
            enabled = true,
            locked = false
        ),
        AgentMemoryRule(
            id = "mem_free_tools",
            title = "100% Free & Open-Source Tools",
            instruction = "Always prefer 100% free, open-source tools, APIs, and models with no paid paywalls or subscriptions.",
            category = "Preference",
            enabled = true,
            locked = true
        ),
        AgentMemoryRule(
            id = "mem_maha_pauranik",
            title = "@MahaPauranik स्क्रिप्ट स्टाइल",
            instruction = "For storytelling, mythology scripts, and voiceover narration, adopt the dramatic @MahaPauranik devotional/cinematic tone.",
            category = "Style",
            enabled = true,
            locked = false
        ),
        AgentMemoryRule(
            id = "mem_4gb_ram_safe",
            title = "4GB RAM Safe Mode",
            instruction = "Keep code modular, lazy-loaded, memory-efficient, and safe for 4GB RAM / 64GB storage Android devices.",
            category = "Safety",
            enabled = true,
            locked = true
        )
    )
}
