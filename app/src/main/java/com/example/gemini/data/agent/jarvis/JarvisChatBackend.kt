package com.shriram.cinecode.data.agent.jarvis

import android.app.Application
import com.shriram.cinecode.data.agent.AgentChatBackend
import com.shriram.cinecode.data.agent.ChatSessionStore
import com.shriram.cinecode.data.agent.ProviderManager
import com.shriram.cinecode.data.agent.opencode.OpenCodePreferences
import com.shriram.cinecode.data.context.GlobalContext
import com.shriram.cinecode.data.jarvis.JarvisController
import com.shriram.cinecode.data.jarvis.JarvisMcp
import com.shriram.cinecode.data.jarvis.JarvisMissionStore
import com.shriram.cinecode.data.jarvis.JarvisPreferences
import com.shriram.cinecode.data.jarvis.JarvisSkill
import com.shriram.cinecode.data.remote.CloudBridgeStore
import com.shriram.cinecode.domain.model.AgentKind
import com.shriram.cinecode.domain.model.AiModel
import com.shriram.cinecode.domain.model.ChatMessage
import com.shriram.cinecode.domain.model.Conversation
import com.shriram.cinecode.domain.model.MessageRole
import com.shriram.cinecode.domain.model.ModelFamily
import com.shriram.cinecode.domain.model.ToolCall
import com.shriram.cinecode.domain.model.ToolType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Interactive Jarvis Chat Agent backend (`AgentKind.JARVIS`) with in-process execution of all `jarvis_*` MCP tools,
 * mission & team orchestration, multi-round Gemini function calling, and OpenAI-compatible fallback endpoints.
 */
class JarvisChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    private val providerManager: ProviderManager,
    private val openCodePrefs: OpenCodePreferences
) : AgentChatBackend {

    companion object {
        const val MODEL_GEMINI_25_FLASH = "gemini-2.5-flash"
        const val MODEL_GEMINI_38_FLASH = "gemini-3.8-flash"
        const val MODEL_GEMINI_20_FLASH = "gemini-2.0-flash"
        const val MODEL_GROQ_LLAMA = "jarvis-groq-llama3"
        const val MODEL_OPENROUTER_FREE = "jarvis-openrouter-free"
        const val MODEL_LOCAL_OLLAMA = "jarvis-local-ollama"

        private const val MAX_TOOL_ROUNDS = 8
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override val kind = AgentKind.JARVIS

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

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _availableModels = MutableStateFlow(
        listOf(
            AiModel(
                id = MODEL_GEMINI_25_FLASH,
                displayName = "Jarvis · Gemini 2.5 Flash (Tools)",
                family = ModelFamily.GEMINI,
                supportsThinking = true,
                description = "Recommended native Gemini model with full 64-tool jarvis_mcp calling",
                isDefault = true
            ),
            AiModel(
                id = MODEL_GEMINI_38_FLASH,
                displayName = "Jarvis · Gemini 3.8 Flash (Tools)",
                family = ModelFamily.GEMINI,
                supportsThinking = true,
                description = "High-speed Gemini model with jarvis_mcp tool orchestration"
            ),
            AiModel(
                id = MODEL_GEMINI_20_FLASH,
                displayName = "Jarvis · Gemini 2.0 Flash",
                family = ModelFamily.GEMINI,
                supportsThinking = false,
                description = "Fast Gemini 2.0 Flash fallback with jarvis_mcp tools"
            ),
            AiModel(
                id = MODEL_GROQ_LLAMA,
                displayName = "Jarvis · Groq Llama 3.3 70B",
                family = ModelFamily.OTHER,
                supportsThinking = false,
                description = "Ultra-fast Groq inference (uses Groq API key from Settings → Providers)"
            ),
            AiModel(
                id = MODEL_OPENROUTER_FREE,
                displayName = "Jarvis · OpenRouter Free",
                family = ModelFamily.OTHER,
                supportsThinking = false,
                description = "Free OpenRouter model (uses OpenRouter or OpenCode API key)"
            ),
            AiModel(
                id = MODEL_LOCAL_OLLAMA,
                displayName = "Jarvis · Local / OpenCode Endpoint",
                family = ModelFamily.OTHER,
                supportsThinking = false,
                description = "Uses OpenCode Base URL (Local Ollama or custom OpenAI-compatible server)"
            )
        )
    )
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val _selectedModelId = MutableStateFlow(MODEL_GEMINI_25_FLASH)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val messagesByConv = mutableMapOf<String, List<ChatMessage>>()
    private var activeJob: Job? = null
    @Volatile
    private var activeCall: Call? = null
    private var activeConvId: String? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun selectModel(modelId: String) {
        val valid = _availableModels.value.firstOrNull { it.id == modelId }?.id ?: return
        _selectedModelId.value = valid
        store.currentConversation.value?.let { current ->
            if (current.agent == AgentKind.JARVIS) {
                val updated = current.copy(modelId = valid)
                store.currentConversation.value = updated
                _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
            }
        }
    }

    override fun startNewChat() {
        val id = "jarvis_${UUID.randomUUID().toString().take(10)}"
        val conv = Conversation(
            id = id,
            title = "Jarvis Assistant",
            modelId = _selectedModelId.value,
            agent = AgentKind.JARVIS
        )
        _conversations.value = listOf(conv) + _conversations.value.filterNot {
            it.title == "Jarvis Assistant" && messagesByConv[it.id].isNullOrEmpty()
        }
        messagesByConv[id] = emptyList()
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        store.currentConversation.value = conv
        _messages.value = messagesByConv[id].orEmpty()
        _conversationError.value = null
    }

    fun loadMessages(id: String): List<ChatMessage> = messagesByConv[id].orEmpty()

    override fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        if (_isStreaming.value) {
            _conversationError.value = "Jarvis is already working on the previous request."
            return
        }

        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.JARVIS } ?: run {
            startNewChat()
            store.currentConversation.value!!
        }
        val convId = conv.id
        val isVoiceTurn = trimmed.contains("[Jarvis")

        val titlePreview = trimmed
            .replace(Regex("<antigem-expansion command=\"[^\"]*\">[\\s\\S]*?</antigem-expansion>"), "")
            .trim()
            .ifBlank { trimmed }
            .lines()
            .first()
            .take(42)
        val updatedTitle = if (conv.title == "Jarvis Assistant" || conv.title == "New Chat") titlePreview else conv.title
        val runningConv = conv.copy(
            title = updatedTitle,
            modelId = _selectedModelId.value,
            updatedAt = System.currentTimeMillis(),
            isRunning = true
        )
        store.currentConversation.value = runningConv
        _conversations.value = _conversations.value.map { if (it.id == convId) runningConv else it }

        val userMsg = ChatMessage(
            conversationId = convId,
            role = MessageRole.USER,
            content = trimmed
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantMsgId,
            conversationId = convId,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            modelId = _selectedModelId.value
        )

        val historyBefore = messagesByConv[convId].orEmpty()
        messagesByConv[convId] = historyBefore + userMsg + placeholder
        if (store.currentConversation.value?.id == convId) {
            _messages.value = messagesByConv[convId].orEmpty()
        }

        _isStreaming.value = true
        _conversationError.value = null
        activeConvId = convId

        activeJob?.cancel()
        activeJob = backendScope.launch(Dispatchers.IO) {
            val recordedTools = mutableListOf<ToolCall>()
            try {
                val reply = runJarvisTurn(
                    convId = convId,
                    isVoiceTurn = isVoiceTurn,
                    historyBefore = historyBefore,
                    userInput = trimmed,
                    onToolProgress = { toolsSnapshot ->
                        recordedTools.clear()
                        recordedTools.addAll(toolsSnapshot)
                        updateAssistantMessage(
                            convId,
                            assistantMsgId,
                            placeholder.copy(toolCalls = recordedTools.toList(), isStreaming = true)
                        )
                    }
                )
                updateAssistantMessage(
                    convId,
                    assistantMsgId,
                    placeholder.copy(
                        content = reply,
                        toolCalls = recordedTools.toList(),
                        isStreaming = false
                    )
                )
            } catch (_: CancellationException) {
                updateAssistantMessage(
                    convId,
                    assistantMsgId,
                    placeholder.copy(
                        content = "Stopped.",
                        toolCalls = recordedTools.toList(),
                        isStreaming = false
                    )
                )
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                _conversationError.value = msg
                updateAssistantMessage(
                    convId,
                    assistantMsgId,
                    placeholder.copy(
                        content = "Jarvis encountered an error: $msg",
                        toolCalls = recordedTools.toList(),
                        isStreaming = false
                    )
                )
            } finally {
                val doneConv = runningConv.copy(isRunning = false, updatedAt = System.currentTimeMillis())
                _conversations.value = _conversations.value.map { if (it.id == convId) doneConv else it }
                if (store.currentConversation.value?.id == convId) {
                    store.currentConversation.value = doneConv
                }
                _isStreaming.value = false
                activeCall = null
                if (activeConvId == convId) activeConvId = null
            }
        }
    }

    private suspend fun runJarvisTurn(
        convId: String,
        isVoiceTurn: Boolean,
        historyBefore: List<ChatMessage>,
        userInput: String,
        onToolProgress: (List<ToolCall>) -> Unit
    ): String {
        val managerId = if (isVoiceTurn) "voice" else convId

        // 1. Direct slash / tool execution works even without an LLM API key
        tryDirectToolCommand(managerId, userInput)?.let { (toolCall, resultText) ->
            onToolProgress(listOf(toolCall))
            return resultText
        }

        val modelId = _selectedModelId.value
        val systemPrompt = buildSystemPrompt(managerId, isVoiceTurn)

        return if (modelId.startsWith("gemini-")) {
            val geminiKey = providerManager.getApiKey(ProviderManager.PROVIDER_GEMINI)
                .ifBlank { CloudBridgeStore.apiKeyFlow.value }
            if (geminiKey.isBlank()) {
                return missingKeyMessage("Gemini")
            }
            executeGeminiWithTools(
                apiKey = geminiKey,
                requestedModel = modelId,
                managerId = managerId,
                isVoiceTurn = isVoiceTurn,
                systemPrompt = systemPrompt,
                historyBefore = historyBefore,
                userInput = userInput,
                onToolProgress = onToolProgress
            )
        } else {
            executeOpenAiCompatibleWithTools(
                modelChoice = modelId,
                managerId = managerId,
                isVoiceTurn = isVoiceTurn,
                systemPrompt = systemPrompt,
                historyBefore = historyBefore,
                userInput = userInput,
                onToolProgress = onToolProgress
            )
        }
    }

    private suspend fun tryDirectToolCommand(managerId: String, input: String): Pair<ToolCall, String>? {
        val trimmed = input.trim()
        val (toolName, rawArgs) = when {
            trimmed.equals("/health", ignoreCase = true) -> "jarvis_get_health" to JSONObject()
            trimmed.equals("/status", ignoreCase = true) || trimmed.equals("/state", ignoreCase = true) ->
                "jarvis_get_state" to JSONObject()
            trimmed.equals("/tasks", ignoreCase = true) -> "jarvis_task_list" to JSONObject()
            trimmed.equals("/chats", ignoreCase = true) -> "jarvis_list_chats" to JSONObject()
            trimmed.equals("/models", ignoreCase = true) -> "jarvis_list_models" to JSONObject()
            trimmed.equals("/memory", ignoreCase = true) -> "jarvis_memory_read" to JSONObject()
            trimmed.equals("/mission", ignoreCase = true) -> "jarvis_mission_get" to JSONObject()
            trimmed.startsWith("/tool ", ignoreCase = true) -> {
                val rest = trimmed.removePrefix("/tool ").trim()
                val name = rest.substringBefore(' ').trim()
                val jsonPart = rest.substringAfter(' ', "").trim()
                val args = runCatching { if (jsonPart.startsWith("{")) JSONObject(jsonPart) else JSONObject() }.getOrDefault(JSONObject())
                name to args
            }
            trimmed.startsWith("jarvis_") && !trimmed.contains('\n') -> {
                val name = trimmed.substringBefore(' ').trim()
                val jsonPart = trimmed.substringAfter(' ', "").trim()
                val args = runCatching { if (jsonPart.startsWith("{")) JSONObject(jsonPart) else JSONObject() }.getOrDefault(JSONObject())
                name to args
            }
            else -> return null
        }

        val args = JSONObject(rawArgs.toString()).apply {
            if (!has("manager_id")) put("manager_id", managerId)
        }
        val res = JarvisMcp.call(application, toolName, args, allowWhenVoiceDisabled = true)
        val outText = extractMcpText(res)
        val ok = !res.optBoolean("isError", false)
        val call = ToolCall(
            name = toolName,
            toolType = ToolType.MCP,
            command = args.toString(),
            status = if (ok) "SUCCESS" else "FAILED",
            output = outText
        )
        return call to outText
    }

    private fun buildSystemPrompt(managerId: String, isVoiceTurn: Boolean): String = buildString {
        appendLine("You are Jarvis, the integrated AI commander and assistant inside CineCode-AI.")
        appendLine(GlobalContext.buildAgentSystemContext())
        appendLine()
        if (isVoiceTurn) {
            appendLine("Voice mode is ACTIVE. Follow Jarvis voice rules strictly: speak via `jarvis_speak` or `jarvis_ask`, and always pass `manager_id=\"voice\"`.")
        } else {
            appendLine("Text chat mode is ACTIVE. Reply directly in clear, concise text or Markdown.")
            appendLine("You can inspect and control the app directly using your `jarvis_*` tools.")
            appendLine("Always pass `manager_id=\"$managerId\"` when calling mission, team, or task tools.")
            appendLine("Only call `jarvis_speak` in text chat if the user explicitly asks you to speak aloud.")
        }
        JarvisSkill.profileSection()?.let {
            appendLine()
            appendLine(it)
        }
        val mem = JarvisPreferences.memory.value.trim()
        if (mem.isNotBlank()) {
            appendLine()
            appendLine("## Jarvis Memory")
            appendLine(mem.take(2500))
        }
        val missionBrief = runCatching { JarvisMissionStore.buildMissionBrief(managerId) }.getOrNull()
        if (!missionBrief.isNullOrBlank()) {
            appendLine()
            appendLine(missionBrief)
        }
        val live = JarvisSkill.liveContext(if (isVoiceTurn) "voice" else "chat")
        if (live.isNotBlank()) {
            appendLine()
            appendLine("## Live App Context")
            appendLine(live)
        }
    }

    private suspend fun executeGeminiWithTools(
        apiKey: String,
        requestedModel: String,
        managerId: String,
        isVoiceTurn: Boolean,
        systemPrompt: String,
        historyBefore: List<ChatMessage>,
        userInput: String,
        onToolProgress: (List<ToolCall>) -> Unit
    ): String {
        val contents = JSONArray()
        historyBefore.filter { !it.isStreaming && it.content.isNotBlank() }.takeLast(12).forEach { msg ->
            val role = if (msg.role == MessageRole.ASSISTANT) "model" else "user"
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userInput)))
        )

        val functionDecls = buildGeminiFunctionDeclarations()
        val toolCallsHistory = mutableListOf<ToolCall>()
        val spokenFallback = StringBuilder()

        val candidatesOrder = listOf(requestedModel, MODEL_GEMINI_25_FLASH, MODEL_GEMINI_20_FLASH).distinct()
        var activeModelIndex = 0

        for (round in 0 until MAX_TOOL_ROUNDS) {
            var responseJson: JSONObject? = null
            var lastErr = "Gemini request failed"

            while (activeModelIndex < candidatesOrder.size) {
                val model = candidatesOrder[activeModelIndex]
                val body = JSONObject().apply {
                    put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                    )
                    put("contents", contents)
                    if (functionDecls.length() > 0) {
                        put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDecls)))
                    }
                    put(
                        "generationConfig",
                        JSONObject()
                            .put("temperature", 0.35)
                            .put("maxOutputTokens", 4096)
                    )
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val req = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .header("Content-Type", "application/json")
                    .build()

                val call = httpClient.newCall(req)
                activeCall = call
                val (code, raw) = call.execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
                activeCall = null

                if (code in 200..299) {
                    responseJson = JSONObject(raw.ifBlank { "{}" })
                    break
                }
                lastErr = "Gemini HTTP $code: ${raw.take(350)}"
                if ((code == 404 || code == 429 || (code == 400 && raw.contains("not found", ignoreCase = true))) &&
                    activeModelIndex + 1 < candidatesOrder.size
                ) {
                    activeModelIndex++
                    continue
                }
                throw IllegalStateException(lastErr)
            }

            val resp = responseJson ?: throw IllegalStateException(lastErr)
            val contentObj = resp.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?: return spokenFallback.toString().ifBlank { "No response returned by Gemini." }

            val parts = contentObj.optJSONArray("parts") ?: JSONArray()
            val textOut = StringBuilder()
            val functionCalls = mutableListOf<Pair<String, JSONObject>>()

            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val txt = p.optString("text", "")
                if (txt.isNotBlank()) {
                    if (textOut.isNotEmpty()) textOut.append('\n')
                    textOut.append(txt)
                }
                p.optJSONObject("functionCall")?.let { fc ->
                    val name = fc.optString("name").trim()
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    if (name.isNotEmpty()) functionCalls += name to args
                }
            }

            if (functionCalls.isEmpty()) {
                val final = textOut.toString().trim()
                return final.ifBlank { spokenFallback.toString().trim().ifBlank { "Done." } }
            }

            // Append model's functionCall turn
            contents.put(
                JSONObject()
                    .put("role", "model")
                    .put("parts", parts)
            )

            val responseParts = JSONArray()
            for ((fnName, fnArgs) in functionCalls) {
                val effectiveArgs = JSONObject(fnArgs.toString()).apply {
                    if (!has("manager_id")) put("manager_id", managerId)
                }
                val runningTool = ToolCall(
                    name = fnName,
                    toolType = ToolType.MCP,
                    command = effectiveArgs.toString(),
                    status = "RUNNING"
                )
                toolCallsHistory += runningTool
                onToolProgress(toolCallsHistory.toList())

                val (ok, resultText) = executeSingleJarvisTool(fnName, effectiveArgs, isVoiceTurn, spokenFallback)
                val finishedTool = runningTool.copy(
                    status = if (ok) "SUCCESS" else "FAILED",
                    output = resultText.take(2000)
                )
                toolCallsHistory[toolCallsHistory.lastIndex] = finishedTool
                onToolProgress(toolCallsHistory.toList())

                responseParts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject()
                            .put("name", fnName)
                            .put(
                                "response",
                                JSONObject().put(if (ok) "result" else "error", resultText)
                            )
                    )
                )
            }

            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", responseParts)
            )
        }

        return spokenFallback.toString().trim().ifBlank {
            "Completed tool calls (reached max tool rounds)."
        }
    }

    private suspend fun executeOpenAiCompatibleWithTools(
        modelChoice: String,
        managerId: String,
        isVoiceTurn: Boolean,
        systemPrompt: String,
        historyBefore: List<ChatMessage>,
        userInput: String,
        onToolProgress: (List<ToolCall>) -> Unit
    ): String {
        val (baseUrl, modelName, apiKey, label) = when (modelChoice) {
            MODEL_GROQ_LLAMA -> Quad(
                "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                providerManager.getApiKey(ProviderManager.PROVIDER_GROQ),
                "Groq"
            )
            MODEL_OPENROUTER_FREE -> Quad(
                "https://openrouter.ai/api/v1",
                OpenCodePreferences.DEFAULT_MODEL,
                providerManager.getApiKey(ProviderManager.PROVIDER_OPENROUTER).ifBlank { openCodePrefs.apiKey.value },
                "OpenRouter"
            )
            else -> Quad(
                openCodePrefs.baseUrl.value.trimEnd('/'),
                openCodePrefs.selectedModel.value,
                openCodePrefs.apiKey.value.ifBlank { providerManager.getApiKey(ProviderManager.PROVIDER_OPENROUTER) },
                "Local / OpenCode"
            )
        }

        val isLocal = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost")
        if (apiKey.isBlank() && !isLocal) {
            return missingKeyMessage(label)
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            historyBefore.filter { !it.isStreaming && it.content.isNotBlank() }.takeLast(12).forEach { msg ->
                val role = if (msg.role == MessageRole.ASSISTANT) "assistant" else "user"
                put(JSONObject().put("role", role).put("content", msg.content))
            }
            put(JSONObject().put("role", "user").put("content", userInput))
        }

        val openAiTools = buildOpenAiTools()
        val toolCallsHistory = mutableListOf<ToolCall>()
        val spokenFallback = StringBuilder()
        var allowTools = true

        for (round in 0 until 6) {
            val body = JSONObject().apply {
                put("model", modelName)
                put("messages", messages)
                put("temperature", 0.35)
                if (allowTools && openAiTools.length() > 0) {
                    put("tools", openAiTools)
                }
            }
            val reqBuilder = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .header("Content-Type", "application/json")
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer $apiKey")
            }

            val call = httpClient.newCall(reqBuilder.build())
            activeCall = call
            val (code, raw) = call.execute().use { resp -> resp.code to resp.body?.string().orEmpty() }
            activeCall = null

            if (code !in 200..299) {
                if (allowTools && (code == 400 || code == 422) && raw.contains("tool", ignoreCase = true)) {
                    allowTools = false
                    continue
                }
                throw IllegalStateException("$label HTTP $code: ${raw.take(350)}")
            }

            val root = JSONObject(raw.ifBlank { "{}" })
            val msgObj = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: return spokenFallback.toString().ifBlank { "No response from $modelName." }

            val contentText = msgObj.optString("content", "").trim()
            val toolCallsArr = msgObj.optJSONArray("tool_calls")
            if (toolCallsArr == null || toolCallsArr.length() == 0) {
                return contentText.ifBlank { spokenFallback.toString().trim().ifBlank { "Done." } }
            }

            messages.put(msgObj)
            for (i in 0 until toolCallsArr.length()) {
                val tc = toolCallsArr.optJSONObject(i) ?: continue
                val tcId = tc.optString("id", UUID.randomUUID().toString())
                val fnObj = tc.optJSONObject("function") ?: continue
                val fnName = fnObj.optString("name").trim()
                val argStr = fnObj.optString("arguments", "{}")
                val argsObj = runCatching { JSONObject(argStr) }.getOrDefault(JSONObject()).apply {
                    if (!has("manager_id")) put("manager_id", managerId)
                }

                val runningTool = ToolCall(
                    name = fnName,
                    toolType = ToolType.MCP,
                    command = argsObj.toString(),
                    status = "RUNNING"
                )
                toolCallsHistory += runningTool
                onToolProgress(toolCallsHistory.toList())

                val (ok, resultText) = executeSingleJarvisTool(fnName, argsObj, isVoiceTurn, spokenFallback)
                toolCallsHistory[toolCallsHistory.lastIndex] = runningTool.copy(
                    status = if (ok) "SUCCESS" else "FAILED",
                    output = resultText.take(2000)
                )
                onToolProgress(toolCallsHistory.toList())

                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", tcId)
                        .put("content", resultText)
                )
            }
        }

        return spokenFallback.toString().trim().ifBlank { "Completed Jarvis actions." }
    }

    private suspend fun executeSingleJarvisTool(
        name: String,
        args: JSONObject,
        isVoiceTurn: Boolean,
        spokenFallback: StringBuilder
    ): Pair<Boolean, String> {
        if (name == "jarvis_speak") {
            val spoken = args.optString("text").trim()
            if (spoken.isNotEmpty()) {
                if (spokenFallback.isNotEmpty()) spokenFallback.append('\n')
                spokenFallback.append(spoken)
            }
            if (!isVoiceTurn && !JarvisPreferences.enabled.value) {
                return true to "Shown in Jarvis chat."
            }
        }
        val res = JarvisMcp.call(application, name, args, allowWhenVoiceDisabled = true)
        val ok = !res.optBoolean("isError", false)
        return ok to extractMcpText(res)
    }

    private fun extractMcpText(res: JSONObject): String {
        val contentArr = res.optJSONArray("content")
        if (contentArr != null && contentArr.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val txt = contentArr.optJSONObject(i)?.optString("text", "").orEmpty()
                if (txt.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(txt)
                }
            }
            if (sb.isNotEmpty()) return sb.toString()
        }
        return res.optString("text").ifBlank { res.toString() }
    }

    private fun buildGeminiFunctionDeclarations(): JSONArray {
        val tools = JarvisMcp.tools()
        val decls = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = t.optString("name").trim()
            if (name.isEmpty()) continue
            val desc = t.optString("description", "")
            val decl = JSONObject()
                .put("name", name)
                .put("description", desc.take(512))
            val sanitizedParams = sanitizeGeminiSchema(t.optJSONObject("inputSchema"))
            val props = sanitizedParams?.optJSONObject("properties")
            if (sanitizedParams != null && props != null && props.length() > 0) {
                decl.put("parameters", sanitizedParams)
            }
            decls.put(decl)
        }
        return decls
    }

    private fun sanitizeGeminiSchema(schema: JSONObject?): JSONObject? {
        if (schema == null) return null
        val out = JSONObject()
        val rawType = schema.optString("type").lowercase()
        val mappedType = when (rawType) {
            "string" -> "STRING"
            "integer" -> "INTEGER"
            "number" -> "NUMBER"
            "boolean" -> "BOOLEAN"
            "array" -> "ARRAY"
            else -> "OBJECT"
        }
        out.put("type", mappedType)
        schema.optString("description").takeIf { it.isNotBlank() }?.let { out.put("description", it.take(300)) }
        schema.optJSONArray("enum")?.takeIf { it.length() > 0 }?.let { out.put("enum", it) }
        if (mappedType == "ARRAY") {
            val itemSchema = sanitizeGeminiSchema(schema.optJSONObject("items")) ?: JSONObject().put("type", "STRING")
            out.put("items", itemSchema)
        }
        if (mappedType == "OBJECT") {
            val srcProps = schema.optJSONObject("properties")
            if (srcProps != null && srcProps.length() > 0) {
                val dstProps = JSONObject()
                val keys = srcProps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val child = srcProps.optJSONObject(k)
                    sanitizeGeminiSchema(child)?.let { dstProps.put(k, it) }
                }
                if (dstProps.length() > 0) {
                    out.put("properties", dstProps)
                    schema.optJSONArray("required")?.takeIf { it.length() > 0 }?.let { out.put("required", it) }
                }
            }
        }
        return out
    }

    private fun buildOpenAiTools(): JSONArray {
        val tools = JarvisMcp.tools()
        val arr = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val name = t.optString("name").trim()
            if (name.isEmpty()) continue
            val params = t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("description", t.optString("description", "").take(512))
                            .put("parameters", params)
                    )
            )
        }
        return arr
    }

    private fun missingKeyMessage(providerLabel: String): String =
        "**Jarvis ($providerLabel)** needs an API key to converse.\n\n" +
            "You can either:\n" +
            "- Add your free **$providerLabel** API key in **Settings → Providers** (or Cloud Bridge),\n" +
            "- Switch the model pill to **Jarvis · Local / OpenCode Endpoint**, or\n" +
            "- Run quick Jarvis commands offline right here: `/health`, `/status`, `/tasks`, `/chats`, `/models`, `/mission`, `/memory`."

    private fun updateAssistantMessage(convId: String, msgId: String, updated: ChatMessage) {
        val currentList = messagesByConv[convId].orEmpty()
        val next = currentList.map { if (it.id == msgId) updated else it }
        messagesByConv[convId] = next
        if (store.currentConversation.value?.id == convId) {
            _messages.value = next
        }
    }

    override fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        activeCall?.cancel()
        activeCall = null
        _isStreaming.value = false
    }

    override fun deleteConversation(id: String) {
        if (activeConvId == id) stopStreaming()
        messagesByConv.remove(id)
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (store.currentConversation.value?.id == id) startNewChat()
    }

    override fun forkConversation(id: String) {
        val orig = _conversations.value.firstOrNull { it.id == id } ?: return
        val newId = "jarvis_${UUID.randomUUID().toString().take(10)}"
        val forked = orig.copy(id = newId, title = "${orig.title} (Fork)", updatedAt = System.currentTimeMillis())
        messagesByConv[newId] = messagesByConv[id].orEmpty()
        _conversations.value = listOf(forked) + _conversations.value
        selectConversation(newId)
    }

    override fun retryConnections() {}
    override fun onAppForegrounded() {}

    fun detach() {
        stopStreaming()
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: String)
}
