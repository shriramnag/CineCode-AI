package com.shriram.cinecode.data.context

import android.app.ActivityManager
import android.content.Context
import com.shriram.cinecode.data.memory.AgentMemory
import com.shriram.cinecode.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GlobalContextSnapshot(
    val activeAgent: AgentKind = AgentKind.AGY,
    val activeProjectName: String = "CineCode-AI",
    val activeProjectPath: String = "",
    val totalRamMb: Long = 4096L,
    val isLowRamDevice: Boolean = true,
    val activeMemoryRuleCount: Int = 0
)

/**
 * GlobalContext holds lightweight workspace + device + memory context shared across all 4 coding agents
 * (Antigravity, Claude Code, Codex, OpenCode) and tools.
 */
object GlobalContext {
    private val _snapshot = MutableStateFlow(GlobalContextSnapshot())
    val snapshot: StateFlow<GlobalContextSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        AgentMemory.init(context)
        if (initialized) return
        initialized = true
        val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memInfo) }
        val totalMb = if (memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 4096L
        _snapshot.value = _snapshot.value.copy(
            totalRamMb = totalMb,
            isLowRamDevice = totalMb <= 4600L || (am?.isLowRamDevice == true),
            activeMemoryRuleCount = AgentMemory.rules.value.count { it.enabled }
        )
    }

    fun updateActiveAgent(agent: AgentKind) {
        _snapshot.value = _snapshot.value.copy(
            activeAgent = agent,
            activeMemoryRuleCount = AgentMemory.rules.value.count { it.enabled }
        )
    }

    fun updateActiveProject(name: String, path: String) {
        _snapshot.value = _snapshot.value.copy(
            activeProjectName = name.ifBlank { "CineCode-AI" },
            activeProjectPath = path
        )
    }

    fun buildAgentSystemContext(): String {
        val snap = _snapshot.value
        val memBlock = AgentMemory.buildPromptMemoryBlock()
        return buildString {
            append("[CineCode-AI Global Context]\n")
            append("Workspace: ").append(snap.activeProjectName)
            if (snap.activeProjectPath.isNotBlank()) {
                append(" (").append(snap.activeProjectPath).append(")")
            }
            append('\n')
            append("Device Profile: ").append(snap.totalRamMb).append(" MB RAM")
            if (snap.isLowRamDevice) append(" (4GB RAM Safe Mode)")
            append('\n')
            if (memBlock.isNotBlank()) {
                append('\n').append(memBlock)
            }
        }.trim()
    }
}
