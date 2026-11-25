package com.shriram.cinecode.data.agent.terminal

import android.app.Application
import com.shriram.cinecode.data.adb.DeviceAccess
import com.shriram.cinecode.data.agent.AgentChatBackend
import com.shriram.cinecode.data.agent.ChatSessionStore
import com.shriram.cinecode.data.daemon.TermuxDaemonManager
import com.shriram.cinecode.data.local.LocalCommandResult
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import com.shriram.cinecode.data.local.LocalTerminalBridge
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Real Terminal chat agent backend supporting:
 * - Local Termux / Ubuntu PRoot shell (`terminal-local`)
 * - Privileged Android Device Shell via ADB / Shizuku / Root (`terminal-device` or `@device`)
 * - Live interactive Terminal Tab PTY routing (`terminal-pty` or `@pty`)
 */
class TerminalChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore
) : AgentChatBackend {

    companion object {
        const val MODEL_LOCAL = "terminal-local"
        const val MODEL_DEVICE = "terminal-device"
        const val MODEL_PTY = "terminal-pty"
        private const val MAX_OUTPUT_CHARS = 24_000
    }

    override val kind = AgentKind.TERMINAL

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

    private val _selectedModelId = MutableStateFlow(MODEL_LOCAL)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()

    private val _availableModels = MutableStateFlow(
        listOf(
            AiModel(
                id = MODEL_LOCAL,
                displayName = "Local Shell (Termux / Ubuntu)",
                family = ModelFamily.OTHER,
                supportsThinking = false,
                description = "Execute bash commands in the app's local Termux or Ubuntu PRoot environment",
                isDefault = true
            ),
            AiModel(
                id = MODEL_DEVICE,
                displayName = "Device Shell (ADB / Shizuku / Root)",
                family = ModelFamily.OTHER,
                supportsThinking = false,
                description = "Execute privileged Android shell commands via configured DeviceAccess mode"
            ),
            AiModel(
                id = MODEL_PTY,
                displayName = "Active Terminal Tab (PTY)",
                family = ModelFamily.OTHER,
                supportsThinking = false,
                description = "Forward commands to the active interactive Terminal tab session"
            )
        )
    )
    val availableModels: StateFlow<List<AiModel>> = _availableModels.asStateFlow()

    private val messagesByConv = mutableMapOf<String, List<ChatMessage>>()
    private val workingDirByConv = mutableMapOf<String, String>()
    private val prevWorkingDirByConv = mutableMapOf<String, String>()
    private var activeJob: Job? = null
    private var activeConvId: String? = null

    private fun defaultWorkingDir(): String {
        val activeProject = TermuxDaemonManager.activeProject.value?.path
        if (!activeProject.isNullOrBlank() && File(activeProject).isDirectory) {
            return activeProject
        }
        return if (!LocalEnvironmentManager.isTermuxPackage(application) && LocalEnvironmentManager.isUbuntuInstalled(application)) {
            "/root"
        } else {
            LocalEnvironmentManager.getHomeDir(application).absolutePath
        }
    }

    private fun workingDirOf(convId: String): String =
        workingDirByConv.getOrPut(convId) { defaultWorkingDir() }

    fun selectModel(modelId: String) {
        val valid = _availableModels.value.firstOrNull { it.id == modelId }?.id ?: MODEL_LOCAL
        _selectedModelId.value = valid
        store.currentConversation.value?.let { current ->
            if (current.agent == AgentKind.TERMINAL) {
                val updated = current.copy(modelId = valid)
                store.currentConversation.value = updated
                _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
            }
        }
    }

    override fun startNewChat() {
        val id = "terminal_${UUID.randomUUID().toString().take(10)}"
        val cwd = defaultWorkingDir()
        val conv = Conversation(
            id = id,
            title = "Terminal Shell",
            modelId = _selectedModelId.value,
            agent = AgentKind.TERMINAL,
            workspaceUri = cwd
        )
        _conversations.value = listOf(conv) + _conversations.value.filterNot {
            it.title == "Terminal Shell" && messagesByConv[it.id].isNullOrEmpty()
        }
        messagesByConv[id] = emptyList()
        workingDirByConv[id] = cwd
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        workingDirByConv.putIfAbsent(id, conv.workspaceUri.ifBlank { defaultWorkingDir() })
        store.currentConversation.value = conv
        _messages.value = messagesByConv[id].orEmpty()
        _conversationError.value = null
    }

    fun loadMessages(id: String): List<ChatMessage> = messagesByConv[id].orEmpty()

    override fun sendMessage(content: String) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return
        if (_isStreaming.value) {
            _conversationError.value = "A command is already running. Stop it before sending another."
            return
        }

        val conv = store.currentConversation.value?.takeIf { it.agent == AgentKind.TERMINAL } ?: run {
            startNewChat()
            store.currentConversation.value!!
        }

        val convId = conv.id
        if (trimmed == "clear" || trimmed == "cls") {
            messagesByConv[convId] = emptyList()
            if (store.currentConversation.value?.id == convId) _messages.value = emptyList()
            _conversationError.value = null
            return
        }

        val updatedTitle = if (conv.title == "Terminal Shell" || conv.title == "New Chat") {
            trimmed.lines().first().take(42)
        } else {
            conv.title
        }
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
        val toolCallId = UUID.randomUUID().toString()
        val initialTool = ToolCall(
            id = toolCallId,
            name = "bash",
            toolType = ToolType.BASH,
            command = trimmed,
            status = "RUNNING"
        )
        val placeholder = ChatMessage(
            id = assistantMsgId,
            conversationId = convId,
            role = MessageRole.ASSISTANT,
            content = "Running `$trimmed`…",
            toolCalls = listOf(initialTool),
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
            try {
                val (mode, commandText) = resolveTargetAndCommand(trimmed)
                val finalResult = if (isPureCdCommand(commandText) && mode != MODEL_PTY) {
                    handleCdCommand(convId, mode, commandText)
                } else {
                    executeWithMode(convId, mode, commandText, assistantMsgId, initialTool)
                }

                val status = if (finalResult.exitCode == 0) "SUCCESS" else "FAILED"
                val outputClean = formatOutput(finalResult.output)
                val finishedTool = initialTool.copy(
                    status = status,
                    output = outputClean,
                    exitCode = finalResult.exitCode,
                    durationMs = finalResult.durationMs
                )
                val summaryBody = buildReplyContent(mode, workingDirOf(convId), finalResult, outputClean)
                updateAssistantMessage(
                    convId,
                    assistantMsgId,
                    placeholder.copy(
                        content = summaryBody,
                        toolCalls = listOf(finishedTool),
                        isStreaming = false
                    )
                )
            } catch (_: CancellationException) {
                val cancelledTool = initialTool.copy(status = "TERMINATED", output = "Command cancelled.")
                updateAssistantMessage(
                    convId,
                    assistantMsgId,
                    placeholder.copy(
                        content = "Command stopped.",
                        toolCalls = listOf(cancelledTool),
                        isStreaming = false
                    )
                )
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                val failedTool = initialTool.copy(status = "FAILED", output = msg, exitCode = 1)
                updateAssistantMessage(
                    convId,
                    assistantMsgId,
                    placeholder.copy(
                        content = "Command failed: $msg",
                        toolCalls = listOf(failedTool),
                        isStreaming = false
                    )
                )
                _conversationError.value = msg
            } finally {
                val finishedConv = runningConv.copy(
                    isRunning = false,
                    workspaceUri = workingDirOf(convId),
                    updatedAt = System.currentTimeMillis()
                )
                _conversations.value = _conversations.value.map { if (it.id == convId) finishedConv else it }
                if (store.currentConversation.value?.id == convId) {
                    store.currentConversation.value = finishedConv
                }
                _isStreaming.value = false
                if (activeConvId == convId) activeConvId = null
            }
        }
    }

    private fun resolveTargetAndCommand(input: String): Pair<String, String> {
        return when {
            input.startsWith("@device ", ignoreCase = true) ->
                MODEL_DEVICE to input.substringAfter(' ').trim()
            input.startsWith("!device ", ignoreCase = true) ->
                MODEL_DEVICE to input.substringAfter(' ').trim()
            input.startsWith("@pty ", ignoreCase = true) ->
                MODEL_PTY to input.substringAfter(' ').trim()
            input.startsWith("@local ", ignoreCase = true) ->
                MODEL_LOCAL to input.substringAfter(' ').trim()
            else -> _selectedModelId.value to input
        }
    }

    private fun isPureCdCommand(cmd: String): Boolean {
        val c = cmd.trim()
        return (c == "cd" || c.startsWith("cd ")) &&
            !c.contains("&&") && !c.contains(";") && !c.contains("||") && !c.contains("\n")
    }

    private suspend fun handleCdCommand(convId: String, mode: String, cmd: String): LocalCommandResult {
        val arg = cmd.removePrefix("cd").trim()
        val cur = workingDirOf(convId)
        val useProot = mode == MODEL_LOCAL &&
            !LocalEnvironmentManager.isTermuxPackage(application) &&
            LocalEnvironmentManager.isUbuntuInstalled(application)

        if (useProot || mode == MODEL_DEVICE) {
            val targetExpr = when {
                arg.isEmpty() || arg == "~" -> if (useProot) "/root" else "/sdcard"
                arg == "-" -> prevWorkingDirByConv[convId] ?: cur
                arg.startsWith("~/") -> (if (useProot) "/root/" else "/sdcard/") + arg.removePrefix("~/")
                else -> arg
            }
            val checkCmd = "cd ${shellQuote(cur)} 2>/dev/null || true; cd ${shellQuote(targetExpr)} && pwd"
            val probe = if (mode == MODEL_DEVICE) {
                DeviceAccess.exec(checkCmd, timeoutSeconds = 10, cwd = cur)
            } else {
                LocalEnvironmentManager.executeCommand(checkCmd, application, timeoutSeconds = 10)
            }
            return if (probe.exitCode == 0) {
                val resolved = probe.output.lines().lastOrNull { it.startsWith("/") }?.trim() ?: cur
                prevWorkingDirByConv[convId] = cur
                workingDirByConv[convId] = resolved
                LocalCommandResult(0, "Working directory: $resolved", probe.durationMs)
            } else {
                probe
            }
        }

        val home = LocalEnvironmentManager.getHomeDir(application)
        val candidate = when {
            arg.isEmpty() || arg == "~" -> home
            arg == "-" -> File(prevWorkingDirByConv[convId] ?: cur)
            arg.startsWith("~/") -> File(home, arg.removePrefix("~/"))
            arg.startsWith("/") -> File(arg)
            else -> File(cur, arg)
        }
        val canonical = runCatching { candidate.canonicalFile }.getOrDefault(candidate)
        return if (canonical.isDirectory) {
            prevWorkingDirByConv[convId] = cur
            workingDirByConv[convId] = canonical.absolutePath
            LocalCommandResult(0, "Working directory: ${canonical.absolutePath}", 0L)
        } else {
            LocalCommandResult(1, "cd: ${canonical.absolutePath}: No such directory", 0L)
        }
    }

    private suspend fun executeWithMode(
        convId: String,
        mode: String,
        command: String,
        assistantMsgId: String,
        initialTool: ToolCall
    ): LocalCommandResult {
        if (command.isBlank()) {
            return LocalCommandResult(0, "(empty command)", 0L)
        }
        val cwd = workingDirOf(convId)

        if (mode == MODEL_DEVICE) {
            return DeviceAccess.exec(command, timeoutSeconds = 120, cwd = cwd)
        }

        if (mode == MODEL_PTY) {
            val session = LocalTerminalBridge.getSession(null)
            if (session != null) {
                val sent = LocalTerminalBridge.sendCommand(session.id, command, appendEnter = true)
                if (sent.isSuccess) {
                    delay(1200)
                    val snap = LocalTerminalBridge.getLastExecution(session.id, 120).getOrNull()
                    val output = snap?.optString("output").orEmpty()
                    val stillRunning = snap?.optBoolean("is_running", false) ?: false
                    val note = if (stillRunning) "\n\n_(Command sent to PTY session '${session.name}'; check Terminal tab for live interactive output.)_" else ""
                    return LocalCommandResult(0, output + note, 1200L)
                }
            }
        }

        val useProot = !LocalEnvironmentManager.isTermuxPackage(application) &&
            LocalEnvironmentManager.isUbuntuInstalled(application)
        val effectiveCommand = if (useProot && cwd.startsWith("/")) {
            "cd ${shellQuote(cwd)} 2>/dev/null || true; $command"
        } else {
            command
        }

        val streamBuf = StringBuilder()
        var lastEmitAt = 0L
        return LocalEnvironmentManager.executeCommand(
            command = effectiveCommand,
            context = application,
            workingDir = cwd,
            timeoutSeconds = 120L,
            onOutputLine = { line ->
                synchronized(streamBuf) {
                    if (streamBuf.length < MAX_OUTPUT_CHARS) {
                        streamBuf.append(line).append('\n')
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastEmitAt >= 180L) {
                        lastEmitAt = now
                        val preview = streamBuf.toString().trimEnd()
                        val liveTool = initialTool.copy(status = "RUNNING", output = preview)
                        updateAssistantMessage(
                            convId,
                            assistantMsgId,
                            ChatMessage(
                                id = assistantMsgId,
                                conversationId = convId,
                                role = MessageRole.ASSISTANT,
                                content = "Running `$command`…\n```\n$preview\n```",
                                toolCalls = listOf(liveTool),
                                isStreaming = true,
                                modelId = mode
                            )
                        )
                    }
                }
            }
        )
    }

    private fun updateAssistantMessage(convId: String, msgId: String, updated: ChatMessage) {
        val currentList = messagesByConv[convId].orEmpty()
        val next = currentList.map { if (it.id == msgId) updated else it }
        messagesByConv[convId] = next
        if (store.currentConversation.value?.id == convId) {
            _messages.value = next
        }
    }

    private fun formatOutput(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length > MAX_OUTPUT_CHARS) {
            trimmed.take(MAX_OUTPUT_CHARS) + "\n… [truncated]"
        } else {
            trimmed
        }
    }

    private fun buildReplyContent(
        mode: String,
        cwd: String,
        result: LocalCommandResult,
        outputClean: String
    ): String {
        val header = when (mode) {
            MODEL_DEVICE -> "Device Shell (${DeviceAccess.activeModeLabel()})"
            MODEL_PTY -> "Terminal Tab (PTY)"
            else -> "Local Shell · $cwd"
        }
        val statusLabel = "exit ${result.exitCode}"
        return if (outputClean.isBlank()) {
            "`$header` finished ($statusLabel, no output)."
        } else {
            "`$header` ($statusLabel)\n```\n$outputClean\n```"
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    override fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        if (_selectedModelId.value == MODEL_PTY) {
            backendScope.launch {
                runCatching { LocalTerminalBridge.killProcess(null, "SIGINT") }
            }
        }
        _isStreaming.value = false
    }

    override fun deleteConversation(id: String) {
        if (activeConvId == id) stopStreaming()
        messagesByConv.remove(id)
        workingDirByConv.remove(id)
        prevWorkingDirByConv.remove(id)
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (store.currentConversation.value?.id == id) startNewChat()
    }

    override fun forkConversation(id: String) {
        val orig = _conversations.value.firstOrNull { it.id == id } ?: return
        val newId = "terminal_${UUID.randomUUID().toString().take(10)}"
        val forked = orig.copy(id = newId, title = "${orig.title} (Fork)", updatedAt = System.currentTimeMillis())
        messagesByConv[newId] = messagesByConv[id].orEmpty()
        workingDirByConv[newId] = workingDirByConv[id] ?: defaultWorkingDir()
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
