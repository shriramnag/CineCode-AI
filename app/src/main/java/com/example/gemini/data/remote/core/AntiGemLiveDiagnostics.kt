package com.shriram.cinecode.data.remote.core

import com.shriram.cinecode.data.daemon.IdeApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiagnosticsSnapshot(
    val activeJvmThreads: Int = 0,
    val threadGroupSummary: String = "",
    val agyRunning: Int = 0,
    val agyQueued: Int = 0,
    val agyConns: Int = 0,
    val agyIdleConns: Int = 0,
    val ideRunning: Int = 0,
    val ideQueued: Int = 0,
    val ideConns: Int = 0,
    val ideIdleConns: Int = 0,
    val ioLagMs: Long = 0,
    val isLagging: Boolean = false,
    val topBlockedThreads: List<String> = emptyList()
)

object AntiGemLiveDiagnostics {
    private val _snapshot = MutableStateFlow(DiagnosticsSnapshot())
    val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    private var refreshJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        refreshOnce(includeStacks = false)
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun refreshOnce(includeStacks: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            runCatching { updateSnapshot(includeStacks) }
        }
    }

    private suspend fun updateSnapshot(includeStacks: Boolean) {
        val startProbe = System.currentTimeMillis()
        var lag = 0L
        try {
            withContext(Dispatchers.IO) {
                lag = System.currentTimeMillis() - startProbe
            }
        } catch (_: Exception) {
            lag = -1L
        }

        val activeThreads = Thread.activeCount()
        var summary = "Active threads: $activeThreads"
        val blockedList = mutableListOf<String>()

        if (includeStacks) {
            val allThreads = Thread.getAllStackTraces()
            summary = allThreads.keys.groupBy { t ->
                when {
                    t.name.startsWith("OkHttp") -> "OkHttp"
                    t.name.startsWith("DefaultDispatcher-worker") -> "Coroutines-IO"
                    t.name.startsWith("pool-") -> "Executors"
                    t.name.contains("Bridge") -> "Bridge"
                    else -> "Other"
                }
            }.map { (group, list) -> "$group: ${list.size}" }.joinToString(" | ")

            allThreads.forEach { (t, stack) ->
                if (t.name.startsWith("DefaultDispatcher") || t.name.startsWith("OkHttp")) {
                    val hasSocketRead = stack.any { it.methodName.contains("read", ignoreCase = true) || it.className.contains("Socket", ignoreCase = true) }
                    if (hasSocketRead) {
                        val frame = stack.firstOrNull { !it.className.startsWith("java.") && !it.className.startsWith("kotlin.") }
                        blockedList.add("${t.name} -> ${frame?.className?.substringAfterLast('.') ?: "Socket"}.${frame?.methodName ?: "read"}:${frame?.lineNumber ?: 0}")
                    }
                }
            }
        }

        val agyDisp = AgyOkHttpClient.client.dispatcher
        val agyPool = AgyOkHttpClient.client.connectionPool

        val ideDisp = IdeApiClient.okHttpClient.dispatcher
        val idePool = IdeApiClient.okHttpClient.connectionPool

        _snapshot.value = DiagnosticsSnapshot(
            activeJvmThreads = activeThreads,
            threadGroupSummary = summary,
            agyRunning = agyDisp.runningCallsCount(),
            agyQueued = agyDisp.queuedCallsCount(),
            agyConns = agyPool.connectionCount(),
            agyIdleConns = agyPool.idleConnectionCount(),
            ideRunning = ideDisp.runningCallsCount(),
            ideQueued = ideDisp.queuedCallsCount(),
            ideConns = idePool.connectionCount(),
            ideIdleConns = idePool.idleConnectionCount(),
            ioLagMs = lag,
            isLagging = lag > 150,
            topBlockedThreads = blockedList.take(6)
        )
    }
}
