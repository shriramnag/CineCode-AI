package com.shriram.cinecode.data.agent.opencode

import android.app.Application
import com.shriram.cinecode.data.agent.AgentChatBackend
import com.shriram.cinecode.data.agent.ChatSessionStore
import com.shriram.cinecode.data.context.GlobalContext
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.domain.model.AgentKind
import com.shriram.cinecode.domain.model.AiModel
import com.shriram.cinecode.domain.model.ChatMessage
import com.shriram.cinecode.domain.model.Conversation
import com.shriram.cinecode.domain.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OpenCode coding agent backend with 200+ free open-source models and zero paid paywalls.
 */
class OpenCodeChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: OpenCodePreferences
) : AgentChatBackend {

    override val kind = AgentKind.OPENCODE

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    val selectedModelId: StateFlow<String> = prefs.selectedModel

    private val messagesByConv = mutableMapOf<String, List<ChatMessage>>()
    private var activeJob: Job? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun ensureCatalogLoaded() {
        if (_availableModels.value.isEmpty()) {
            _availableModels.value = OpenCodeModelsCatalog.models
        }
    }

    override fun startNewChat() {
        ensureCatalogLoaded()
        val id = "opencode_${UUID.randomUUID().toString().take(10)}"
        val conv = Conversation(
            id = id,
            title = "New Chat",
            modelId = prefs.selectedModel.value,
            agent = AgentKind.OPENCODE
        )
        _conversations.value = listOf(conv) + _conversations.value.filterNot { it.title == "New Chat" && messagesByConv[it.id].isNullOrEmpty() }
        messagesByConv[id] = emptyList()
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
    }

    override fun selectConversation(id: String) {
        ensureCatalogLoaded()
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        store.currentConversation.value = conv
        _messages.value = messagesByConv[id].orEmpty()
        _conversationError.value = null
    }

    fun loadMessages(id: String): List<ChatMessage> = messagesByConv[id].orEmpty()

    override fun sendMessage(content: String) {
        ensureCatalogLoaded()
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.OPENCODE } ?: run {
            startNewChat()
            store.currentConversation.value!!
        }

        val updatedTitle = if (conv.title == "New Chat") trimmed.take(42) else conv.title
        val updatedConv = conv.copy(title = updatedTitle, updatedAt = System.currentTimeMillis(), isRunning = true)
        store.currentConversation.value = updatedConv
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }

        val userMsg = ChatMessage(conversationId = updatedConv.id, role = MessageRole.USER, content = trimmed)
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantMsgId,
            conversationId = updatedConv.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            modelId = prefs.selectedModel.value
        )

        val historyBefore = messagesByConv[updatedConv.id].orEmpty()
        val nextList = historyBefore + userMsg + placeholder
        messagesByConv[updatedConv.id] = nextList
        if (store.currentConversation.value?.id == updatedConv.id) _messages.value = nextList

        _isStreaming.value = true
        _conversationError.value = null

        activeJob?.cancel()
        activeJob = backendScope.launch {
            val reply = executeCompletion(historyBefore + userMsg)
            val finalAssistant = placeholder.copy(content = reply, isStreaming = false)
            val completedList = messagesByConv[updatedConv.id].orEmpty().map {
                if (it.id == assistantMsgId) finalAssistant else it
            }
            messagesByConv[updatedConv.id] = completedList
            if (store.currentConversation.value?.id == updatedConv.id) _messages.value = completedList
            val doneConv = updatedConv.copy(isRunning = false, updatedAt = System.currentTimeMillis())
            _conversations.value = _conversations.value.map { if (it.id == doneConv.id) doneConv else it }
            if (store.currentConversation.value?.id == doneConv.id) store.currentConversation.value = doneConv
            _isStreaming.value = false
        }
    }

    private suspend fun executeCompletion(history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val baseUrl = prefs.baseUrl.value.trimEnd('/')
        val model = prefs.selectedModel.value
        val key = prefs.apiKey.value.ifBlank { CloudBridgeStore.apiKeyFlow.value }
        val isLocal = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost")

        if (key.isBlank() && !isLocal) {
            return@withContext "⚠️ **OpenCode (200+ Free Models) Ready**: कृपया Settings → Codex & OpenCode में अपनी फ्री API Key डालें या Local Ollama चुनें।"
        }

        val effectiveBaseUrl = if (prefs.apiKey.value.isBlank() && CloudBridgeStore.apiKeyFlow.value.isNotBlank() && !isLocal) {
            "https://generativelanguage.googleapis.com/v1beta/openai"
        } else baseUrl

        val effectiveModel = if (prefs.apiKey.value.isBlank() && CloudBridgeStore.apiKeyFlow.value.isNotBlank() && !isLocal) {
            "gemini-2.5-flash"
        } else model

        val sysPrompt = GlobalContext.buildAgentSystemContext() + "\nYou are OpenCode Open-Source Coding Agent inside CineCode-AI."
        val messagesJson = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", sysPrompt))
            history.takeLast(12).forEach { m ->
                val role = if (m.role == MessageRole.USER) "user" else "assistant"
                put(JSONObject().put("role", role).put("content", m.content))
            }
        }

        val bodyJson = JSONObject().apply {
            put("model", effectiveModel)
            put("messages", messagesJson)
            put("temperature", 0.3)
        }

        val reqBuilder = Request.Builder()
            .url("$effectiveBaseUrl/chat/completions")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
        if (key.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $key")
        }

        runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@runCatching "OpenCode HTTP ${resp.code}: ${raw.take(400)}"
                }
                val root = JSONObject(raw)
                val content = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                content?.takeIf { it.isNotBlank() } ?: "No response from $effectiveModel"
            }
        }.getOrElse { err ->
            "OpenCode Connection Error: ${err.message ?: err.javaClass.simpleName}"
        }
    }

    fun selectModel(modelId: String) {
        prefs.setSelectedModel(modelId)
    }

    override fun stopStreaming() {
        activeJob?.cancel()
        _isStreaming.value = false
    }

    override fun deleteConversation(id: String) {
        messagesByConv.remove(id)
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (store.currentConversation.value?.id == id) startNewChat()
    }

    override fun forkConversation(id: String) {
        val orig = _conversations.value.firstOrNull { it.id == id } ?: return
        val newId = "opencode_${UUID.randomUUID().toString().take(10)}"
        val forked = orig.copy(id = newId, title = "${orig.title} (Fork)", updatedAt = System.currentTimeMillis())
        messagesByConv[newId] = messagesByConv[id].orEmpty()
        _conversations.value = listOf(forked) + _conversations.value
        selectConversation(newId)
    }

    override fun retryConnections() {}
    override fun onAppForegrounded() {}

    fun detach() {
        activeJob?.cancel()
        _isStreaming.value = false
    }
}
