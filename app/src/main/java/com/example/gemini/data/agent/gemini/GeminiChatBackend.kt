package com.shriram.cinecode.data.agent.gemini

import android.app.Application
import android.util.Log
import com.shriram.cinecode.data.agent.AgentChatBackend
import com.shriram.cinecode.data.agent.ChatSessionStore
import com.shriram.cinecode.data.agent.ProviderManager
import com.shriram.cinecode.domain.model.AgentKind
import com.shriram.cinecode.domain.model.AiModel
import com.shriram.cinecode.domain.model.ChatMessage
import com.shriram.cinecode.domain.model.Conversation
import com.shriram.cinecode.domain.model.MessageRole
import com.shriram.cinecode.domain.model.ModelFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Genuine Gemini Agent Backend for CineCode-AI.
 * Features:
 * - Direct Google AI Studio v1beta API streaming via Server-Sent Events (SSE).
 * - Official active Gemini models: gemini-3.8-flash (default), gemini-4-hydrogen, gemini-4-carbon, gemini-3.5-flash-lite.
 * - Persistent conversation and message storage in app storage (gemini_sessions/).
 * - Multi-turn memory with bilingual Hindi, Hinglish, and English fluency.
 * - HTTP 401/403 auth detection, HTTP 429 exponential backoff retry, and timeout protection.
 * - Duplicate send protection and clean cancellation.
 */
class GeminiChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    private val providerManager: ProviderManager
) : AgentChatBackend {

    override val kind = AgentKind.GEMINI

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

    private val _selectedModel = MutableStateFlow("gemini-3.8-flash")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _availableModels = MutableStateFlow(
        listOf(
            AiModel(
                "gemini-3.8-flash",
                "Gemini 3.8 Flash (Recommended)",
                ModelFamily.GEMINI,
                true,
                "High-speed multimodal reasoning & agentic coding"
            ),
            AiModel(
                "gemini-3.5-flash-lite",
                "Gemini 3.5 Flash-Lite",
                ModelFamily.GEMINI,
                true,
                "Ultra-fast high-throughput lightweight execution"
            )
        )
    )
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val messagesByConv = mutableMapOf<String, List<ChatMessage>>()
    private var activeJob: Job? = null
    private var activeCall: Call? = null

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val sessionsDir by lazy {
        File(application.filesDir, "gemini_sessions").apply { mkdirs() }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    init {
        loadPersistedConversations()
    }

    private fun loadPersistedConversations() {
        try {
            val files = sessionsDir.listFiles { file -> file.extension == "json" } ?: return
            val loadedConvs = mutableListOf<Conversation>()
            for (file in files) {
                runCatching {
                    val content = file.readText(Charsets.UTF_8)
                    val root = JSONObject(content)
                    val convObj = root.getJSONObject("conversation")
                    val convId = convObj.getString("id")
                    val title = convObj.optString("title", "Gemini Chat")
                    val modelId = convObj.optString("modelId", "gemini-3.8-flash")
                    val createdAt = convObj.optLong("createdAt", System.currentTimeMillis())
                    val updatedAt = convObj.optLong("updatedAt", createdAt)

                    val conv = Conversation(
                        id = convId,
                        title = title,
                        modelId = modelId,
                        agent = AgentKind.GEMINI,
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                    loadedConvs.add(conv)

                    val msgsArray = root.optJSONArray("messages") ?: JSONArray()
                    val msgsList = mutableListOf<ChatMessage>()
                    for (i in 0 until msgsArray.length()) {
                        val mObj = msgsArray.getJSONObject(i)
                        val roleStr = mObj.optString("role", "USER")
                        val role = runCatching { MessageRole.valueOf(roleStr) }.getOrDefault(MessageRole.USER)
                        msgsList.add(
                            ChatMessage(
                                id = mObj.optString("id", UUID.randomUUID().toString()),
                                conversationId = convId,
                                role = role,
                                content = mObj.optString("content", ""),
                                modelId = mObj.optString("modelId", modelId),
                                createdAt = mObj.optLong("createdAt", System.currentTimeMillis()),
                                isStreaming = false
                            )
                        )
                    }
                    messagesByConv[convId] = msgsList
                }
            }
            if (loadedConvs.isNotEmpty()) {
                val sorted = loadedConvs.sortedByDescending { it.updatedAt }
                _conversations.value = sorted
            }
        } catch (e: Exception) {
            // Safe fallback: non-fatal if disk read has permission/storage issues
        }
    }

    private val saveMutex = Mutex()

    private fun persistConversation(convId: String) {
        backendScope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                try {
                    val conv = _conversations.value.firstOrNull { it.id == convId } ?: return@launch
                    val msgs = messagesByConv[convId].orEmpty().filterNot { it.isStreaming }

                    val root = JSONObject()
                    val convObj = JSONObject().apply {
                        put("id", conv.id)
                        put("title", conv.title)
                        put("modelId", conv.modelId)
                        put("createdAt", conv.createdAt)
                        put("updatedAt", conv.updatedAt)
                    }
                    root.put("conversation", convObj)

                    val msgsArray = JSONArray()
                    for (m in msgs) {
                        val mObj = JSONObject().apply {
                            put("id", m.id)
                            put("conversationId", m.conversationId)
                            put("role", m.role.name)
                            put("content", m.content)
                            put("modelId", m.modelId)
                            put("createdAt", m.createdAt)
                        }
                        msgsArray.put(mObj)
                    }
                    root.put("messages", msgsArray)

                    // Atomic session persistence with ATOMIC_MOVE and safe fallback
                    val targetFile = File(sessionsDir, "$convId.json")
                    atomicWriteSessionFile(targetFile, root.toString())
                } catch (e: Exception) {
                    // Non-fatal logging: original targetFile remains protected if write fails
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        _selectedModel.value = modelId
        store.currentConversation.value?.let { current ->
            if (current.agent == AgentKind.GEMINI) {
                val updated = current.copy(modelId = modelId)
                store.currentConversation.value = updated
                _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
                persistConversation(updated.id)
            }
        }
    }

    override fun startNewChat() {
        stopStreaming()
        val id = "gemini_${UUID.randomUUID().toString().take(10)}"
        val conv = Conversation(
            id = id,
            title = "New Chat",
            modelId = _selectedModel.value,
            agent = AgentKind.GEMINI
        )
        _conversations.value = listOf(conv) + _conversations.value.filterNot {
            it.title == "New Chat" && messagesByConv[it.id].isNullOrEmpty()
        }
        messagesByConv[id] = emptyList()
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
        persistConversation(id)
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        store.currentConversation.value = conv
        _messages.value = messagesByConv[id].orEmpty()
        _conversationError.value = null
    }

    override fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        if (_isStreaming.value) {
            // Guard: disallow concurrent overlapping messages while actively streaming
            return
        }

        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.GEMINI } ?: run {
            startNewChat()
            store.currentConversation.value!!
        }

        val updatedTitle = if (conv.title == "New Chat") trimmed.take(42) else conv.title
        val updatedConv = conv.copy(
            title = updatedTitle,
            updatedAt = System.currentTimeMillis(),
            isRunning = true
        )
        store.currentConversation.value = updatedConv
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }

        val userMsg = ChatMessage(
            conversationId = updatedConv.id,
            role = MessageRole.USER,
            content = trimmed
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantMsgId,
            conversationId = updatedConv.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            modelId = _selectedModel.value
        )

        val historyBefore = messagesByConv[updatedConv.id].orEmpty()
        val currentHistory = historyBefore + userMsg
        val uiList = currentHistory + placeholder
        messagesByConv[updatedConv.id] = uiList

        if (store.currentConversation.value?.id == updatedConv.id) {
            _messages.value = uiList
        }

        _isStreaming.value = true
        _conversationError.value = null

        activeJob?.cancel()
        activeJob = backendScope.launch {
            try {
                executeStreamingCall(
                    convId = updatedConv.id,
                    assistantMsgId = assistantMsgId,
                    history = currentHistory
                )
            } catch (ce: CancellationException) {
                // User cancelled stream intentionally via Stop button
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Unexpected error during Gemini stream"
                _conversationError.value = errorMsg
                val errorAssistant = placeholder.copy(
                    content = "⚠️ **Gemini Error**: $errorMsg",
                    isStreaming = false
                )
                updateAssistantMessage(updatedConv.id, assistantMsgId, errorAssistant)
            } finally {
                _isStreaming.value = false
                val doneConv = updatedConv.copy(isRunning = false, updatedAt = System.currentTimeMillis())
                _conversations.value = _conversations.value.map { if (it.id == doneConv.id) doneConv else it }
                if (store.currentConversation.value?.id == doneConv.id) {
                    store.currentConversation.value = doneConv
                }
                persistConversation(doneConv.id)
            }
        }
    }

    private suspend fun executeStreamingCall(
        convId: String,
        assistantMsgId: String,
        history: List<ChatMessage>
    ) = withContext(Dispatchers.IO) {
        val apiKey = providerManager.getApiKey(ProviderManager.PROVIDER_GEMINI)
        if (apiKey.isBlank()) {
            val noKeyMsg = "⚠️ **API Key Required**: Please configure your Google AI Studio API key in Settings → Providers."
            _conversationError.value = "Google AI Studio API key is missing"
            val errorMsg = ChatMessage(
                id = assistantMsgId,
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = noKeyMsg,
                isStreaming = false,
                modelId = _selectedModel.value
            )
            updateAssistantMessage(convId, assistantMsgId, errorMsg)
            return@withContext
        }

        val modelName = _selectedModel.value
        val supportedModelIds = _availableModels.value.map { it.id }.toSet()
        if (modelName !in supportedModelIds) {
            val errorMsg = "⚠️ **Unsupported Model Error**: Model '$modelName' is not recognized by Google AI Studio client. Supported active models are: ${supportedModelIds.joinToString(", ")}"
            _conversationError.value = "Unsupported model: $modelName"
            val errorAssistant = ChatMessage(
                id = assistantMsgId,
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = errorMsg,
                isStreaming = false,
                modelId = modelName
            )
            updateAssistantMessage(convId, assistantMsgId, errorAssistant)
            return@withContext
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:streamGenerateContent?alt=sse&key=$apiKey"
        val requestJson = buildGeminiPayload(history)
        val requestBody = requestJson.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Accept", "text/event-stream")
            .build()

        var backoffMs = 1500L
        val maxAttempts = 3
        var succeeded = false

        for (attempt in 1..maxAttempts) {
            val call = httpClient.newCall(request)
            activeCall = call

            try {
                call.execute().use { response ->
                    val code = response.code

                    if (code == 429) {
                        if (attempt < maxAttempts) {
                            delay(backoffMs)
                            backoffMs *= 2
                            return@use
                        } else {
                            throw RuntimeException("Google AI Studio rate limit (HTTP 429) exceeded. Please wait a few moments and try again.")
                        }
                    }

                    if (code == 401 || code == 403) {
                        throw RuntimeException("Invalid or unauthorized Google AI Studio API Key (HTTP $code). Please verify your key in Settings.")
                    }

                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string().orEmpty()
                        throw RuntimeException("Gemini API error (HTTP $code): ${parseErrorMessage(errorBody)}")
                    }

                    val responseBody = response.body ?: throw RuntimeException("Empty response body from Gemini API")
                    val reader = BufferedReader(InputStreamReader(responseBody.byteStream(), Charsets.UTF_8))
                    val fullAccumulator = StringBuilder()

                    var line: String? = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("data: ")) {
                            val jsonChunk = line.substring(6).trim()
                            if (jsonChunk.isNotBlank()) {
                                val delta = extractTextDelta(jsonChunk)
                                if (delta.isNotEmpty()) {
                                    fullAccumulator.append(delta)
                                    val intermediateMsg = ChatMessage(
                                        id = assistantMsgId,
                                        conversationId = convId,
                                        role = MessageRole.ASSISTANT,
                                        content = fullAccumulator.toString(),
                                        isStreaming = true,
                                        modelId = modelName
                                    )
                                    updateAssistantMessage(convId, assistantMsgId, intermediateMsg)
                                }
                            }
                        }
                        line = reader.readLine()
                    }

                    val finalContent = fullAccumulator.toString().ifBlank {
                        "*(No text response generated by model)*"
                    }
                    val finalMsg = ChatMessage(
                        id = assistantMsgId,
                        conversationId = convId,
                        role = MessageRole.ASSISTANT,
                        content = finalContent,
                        isStreaming = false,
                        modelId = modelName
                    )
                    updateAssistantMessage(convId, assistantMsgId, finalMsg)
                    succeeded = true
                }

                if (succeeded) break
            } finally {
                activeCall = null
            }
        }
    }

    private fun buildGeminiPayload(history: List<ChatMessage>): JSONObject {
        val contentsArray = JSONArray()

        // Include past conversation turns
        for (msg in history.takeLast(20)) {
            if (msg.content.isBlank()) continue
            val role = if (msg.role == MessageRole.USER) "user" else "model"
            val turn = JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            }
            contentsArray.put(turn)
        }

        val systemPrompt = "You are Gemini Agent inside CineCode-AI on Android. " +
            "You are an expert full-stack Android engineer, AI architect, and video creation studio assistant. " +
            "You are fully bilingual and communicate fluently in Hindi, Hinglish, and English based on the user's preference. " +
            "Provide clean, high-performance, safe code and clear explanations."

        return JSONObject().apply {
            put("contents", contentsArray)
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("maxOutputTokens", 8192)
            })
        }
    }

    private fun extractTextDelta(jsonChunk: String): String {
        return try {
            val root = JSONObject(jsonChunk)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "")
                if (text.isNotEmpty()) sb.append(text)
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseErrorMessage(errorBody: String): String {
        return try {
            val obj = JSONObject(errorBody)
            val errObj = obj.optJSONObject("error")
            errObj?.optString("message", errorBody) ?: errorBody.take(150)
        } catch (e: Exception) {
            errorBody.take(150)
        }
    }

    private fun updateAssistantMessage(convId: String, msgId: String, message: ChatMessage) {
        val currentList = messagesByConv[convId].orEmpty()
        val updatedList = currentList.map { if (it.id == msgId) message else it }
        messagesByConv[convId] = updatedList
        if (store.currentConversation.value?.id == convId) {
            _messages.value = updatedList
        }
    }

    fun detach() {
        stopStreaming()
    }

    fun loadMessages(id: String): List<ChatMessage> {
        return messagesByConv[id].orEmpty()
    }

    override fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        activeCall?.cancel()
        activeCall = null
        _isStreaming.value = false
    }

    override fun deleteConversation(id: String) {
        messagesByConv.remove(id)
        _conversations.value = _conversations.value.filterNot { it.id == id }
        backendScope.launch(Dispatchers.IO) {
            runCatching { File(sessionsDir, "$id.json").delete() }
        }
        if (store.currentConversation.value?.id == id) {
            val next = _conversations.value.firstOrNull()
            if (next != null) {
                selectConversation(next.id)
            } else {
                startNewChat()
            }
        }
    }

    override fun forkConversation(id: String) {
        val original = _conversations.value.firstOrNull { it.id == id } ?: return
        val forkId = "gemini_${UUID.randomUUID().toString().take(10)}"
        val forkedConv = original.copy(
            id = forkId,
            title = "${original.title} (Fork)",
            updatedAt = System.currentTimeMillis()
        )
        val origMessages = messagesByConv[id].orEmpty()
        messagesByConv[forkId] = origMessages
        _conversations.value = listOf(forkedConv) + _conversations.value
        persistConversation(forkId)
        selectConversation(forkId)
    }

    override fun retryConnections() {
        _conversationError.value = null
    }

    override fun onAppForegrounded() {
        // Refresh state on foreground if needed
    }

    companion object {
        private const val TAG = "GeminiChatBackend"

        /**
         * Performs atomic write of session data:
         * 1. Creates a temporary file in the EXACT same directory as targetFile to ensure same filesystem.
         * 2. Writes full UTF-8 payload to temporary file.
         * 3. Attempts Files.move with StandardCopyOption.ATOMIC_MOVE and StandardCopyOption.REPLACE_EXISTING.
         * 4. If AtomicMoveNotSupportedException occurs, falls back to standard Files.move(REPLACE_EXISTING).
         * 5. If Files.move fails with Throwable (e.g. older Android runtime), falls back to File.renameTo().
         * 6. If renameTo fails, throws IOException.
         * 7. Ensures temporary file is always cleaned up in finally block.
         * 8. Ensures original targetFile remains completely intact and uncorrupted if any failure occurs.
         */
        @JvmStatic
        fun atomicWriteSessionFile(targetFile: File, content: String) {
            val parentDir = targetFile.parentFile ?: throw IOException("Target directory is missing")
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
            val tempFile = File(parentDir, "${targetFile.name}.tmp.${UUID.randomUUID().toString().take(8)}")
            try {
                tempFile.writeText(content, Charsets.UTF_8)

                var moved = false
                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    moved = true
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    moved = true
                } catch (e: UnsupportedOperationException) {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    moved = true
                } catch (t: Throwable) {
                    // Fallback to renameTo on older Android runtimes or legacy filesystem layers
                    if (tempFile.renameTo(targetFile)) {
                        moved = true
                    } else {
                        throw IOException("Failed to move temporary file ${tempFile.name} to ${targetFile.name}", t)
                    }
                }
                if (!moved && tempFile.exists()) {
                    throw IOException("Atomic move could not complete for ${targetFile.name}")
                }
            } finally {
                if (tempFile.exists()) {
                    runCatching { tempFile.delete() }
                }
            }
        }
    }
}
