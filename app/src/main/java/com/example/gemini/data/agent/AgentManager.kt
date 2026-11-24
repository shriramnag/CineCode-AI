package com.shriram.cinecode.data.agent

import com.shriram.cinecode.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified Agent Manager coordinating the 7 specialized AI agents in CineCode-AI.
 * Routes user prompts, stream lifecycles, and conversations to the active backend.
 */
class AgentManager(
    private val store: ChatSessionStore,
    private val providerManager: ProviderManager
) {

    private val backends = mutableMapOf<AgentKind, AgentChatBackend>()

    private val _activeAgent = MutableStateFlow(AgentKind.AGY)
    val activeAgent: StateFlow<AgentKind> = _activeAgent.asStateFlow()

    fun registerBackend(backend: AgentChatBackend) {
        backends[backend.kind] = backend
    }

    fun getBackend(kind: AgentKind): AgentChatBackend? = backends[kind]

    fun getActiveBackend(): AgentChatBackend? = backends[_activeAgent.value]

    fun selectAgent(kind: AgentKind) {
        if (_activeAgent.value == kind) return
        getActiveBackend()?.stopStreaming()
        _activeAgent.value = kind
        store.currentConversation.value?.let { current ->
            if (current.agent != kind) {
                // If switching agent, ensure a fresh chat is prepared if the current one is empty
                val curMessages = store.messages.value
                if (curMessages.isEmpty() && current.title == "New Chat") {
                    startNewChat(kind)
                }
            }
        }
    }

    fun startNewChat(agent: AgentKind = _activeAgent.value) {
        _activeAgent.value = agent
        backends[agent]?.startNewChat()
    }

    fun sendMessage(content: String) {
        val backend = getActiveBackend() ?: backends[AgentKind.AGY]
        backend?.sendMessage(content)
    }

    fun stopStreaming() {
        getActiveBackend()?.stopStreaming()
    }

    fun getAvailableAgents(): List<AgentMetadata> = AgentRegistry.getAllAgents()
}
