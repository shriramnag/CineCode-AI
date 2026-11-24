package com.shriram.cinecode.data.agent

import com.shriram.cinecode.domain.model.AgentKind

/**
 * Metadata definition for an AI Agent within the CineCode-AI platform.
 */
data class AgentMetadata(
    val kind: AgentKind,
    val displayName: String,
    val role: String,
    val description: String,
    val iconEmoji: String,
    val supportedModels: List<String>,
    val defaultModel: String,
    val requiresApiKey: Boolean,
    val providerKey: String,
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = false,
    val supportsVoice: Boolean = false,
    val requiresApprovalForExecution: Boolean = false
)

/**
 * Declarative Agent Registry defining the 7 specialized agents in CineCode-AI.
 */
object AgentRegistry {

    val AGY_METADATA = AgentMetadata(
        kind = AgentKind.AGY,
        displayName = "Main AI Assistant",
        role = "System & Workspace Orchestrator",
        description = "Core Antigravity development assistant. Coordinates code reasoning, multi-turn editing, and tool execution.",
        iconEmoji = "🧠",
        supportedModels = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"),
        defaultModel = "gemini-3.8-flash",
        requiresApiKey = false,
        providerKey = "antigravity",
        supportsTools = true
    )

    val GEMINI_METADATA = AgentMetadata(
        kind = AgentKind.GEMINI,
        displayName = "Gemini Agent",
        role = "High-Speed Multimodal & Bilingual Engine",
        description = "Direct Google AI Studio client. Features ultra-low latency token streaming and native fluency in Hindi, Hinglish, and English.",
        iconEmoji = "⚡",
        supportedModels = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite"
        ),
        defaultModel = "gemini-3.8-flash",
        requiresApiKey = true,
        providerKey = "gemini",
        supportsTools = true
    )

    val CLAUDE_METADATA = AgentMetadata(
        kind = AgentKind.CLAUDE,
        displayName = "Claude Agent",
        role = "Autonomous Refactoring & MCP Specialist",
        description = "Anthropic Claude Code integration via Model Context Protocol (MCP). Excels at complex codebases and self-healing patches.",
        iconEmoji = "🤖",
        supportedModels = listOf("claude-3-5-sonnet", "claude-3-5-haiku", "claude-3-opus"),
        defaultModel = "claude-3-5-sonnet",
        requiresApiKey = true,
        providerKey = "anthropic",
        supportsTools = true
    )

    val CODEX_METADATA = AgentMetadata(
        kind = AgentKind.CODEX,
        displayName = "Codex Coding Agent",
        role = "Rapid Code Generation & Unit Testing",
        description = "Free and open AI coding agent compatible with OpenRouter, Groq, and DeepSeek endpoints.",
        iconEmoji = "💻",
        supportedModels = listOf("deepseek-coder", "codellama-70b", "qwen-2.5-coder"),
        defaultModel = "deepseek-coder",
        requiresApiKey = false,
        providerKey = "openrouter"
    )

    val OPENCODE_METADATA = AgentMetadata(
        kind = AgentKind.OPENCODE,
        displayName = "OpenCode Agent",
        role = "Open-Source & Local Models Router",
        description = "Connects to 200+ open-source models and local Ollama instances running on Termux or localhost.",
        iconEmoji = "🌐",
        supportedModels = listOf("llama-3.3-70b", "mistral-large", "phi-4", "local-ollama"),
        defaultModel = "llama-3.3-70b",
        requiresApiKey = false,
        providerKey = "opencode"
    )

    val TERMINAL_METADATA = AgentMetadata(
        kind = AgentKind.TERMINAL,
        displayName = "Terminal Agent",
        role = "Safe Linux Shell & Automation Engine",
        description = "Formulates and executes Termux bash commands with interactive user approval to prevent destructive actions.",
        iconEmoji = "📟",
        supportedModels = listOf("bash-pty-v1"),
        defaultModel = "bash-pty-v1",
        requiresApiKey = false,
        providerKey = "local_pty",
        requiresApprovalForExecution = true
    )

    val JARVIS_METADATA = AgentMetadata(
        kind = AgentKind.JARVIS,
        displayName = "Jarvis Voice Agent",
        role = "Bilingual Speech Assistant & Automation",
        description = "Real-time hands-free voice assistant supporting natural Hindi, Hinglish, and English audio conversations.",
        iconEmoji = "🎙️",
        supportedModels = listOf("jarvis-live-speech"),
        defaultModel = "jarvis-live-speech",
        requiresApiKey = false,
        providerKey = "voice_stream",
        supportsVoice = true
    )

    private val ALL_AGENTS = listOf(
        AGY_METADATA,
        GEMINI_METADATA,
        CLAUDE_METADATA,
        CODEX_METADATA,
        OPENCODE_METADATA,
        TERMINAL_METADATA,
        JARVIS_METADATA
    )

    fun getAllAgents(): List<AgentMetadata> = ALL_AGENTS

    fun getMetadata(kind: AgentKind): AgentMetadata = when (kind) {
        AgentKind.AGY -> AGY_METADATA
        AgentKind.GEMINI -> GEMINI_METADATA
        AgentKind.CLAUDE -> CLAUDE_METADATA
        AgentKind.CODEX -> CODEX_METADATA
        AgentKind.OPENCODE -> OPENCODE_METADATA
        AgentKind.TERMINAL -> TERMINAL_METADATA
        AgentKind.JARVIS -> JARVIS_METADATA
    }
}
