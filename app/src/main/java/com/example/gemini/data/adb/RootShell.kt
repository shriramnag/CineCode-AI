package com.shriram.cinecode.data.adb

import android.util.Log
import com.shriram.cinecode.data.local.LocalCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class RootProbeState {
    UNKNOWN,
    PROBING,
    GRANTED,
    DENIED,
    NO_SU
}

data class RootStatus(
    val state: RootProbeState = RootProbeState.UNKNOWN,
    val suBinary: String? = null,
    val detail: String? = null
) {
    val isReady: Boolean get() = state == RootProbeState.GRANTED
}

/**
 * Probes `su` access and runs root shell commands (Magisk, KernelSU, APatch, etc.).
 */
object RootShell {
    private const val TAG = "RootShell"
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su",
        "su"
    )

    private val _status = MutableStateFlow(RootStatus())
    val status: StateFlow<RootStatus> = _status.asStateFlow()

    fun findSuBinary(): String? {
        for (candidate in SU_CANDIDATES) {
            if (candidate == "su") return "su"
            val f = File(candidate)
            if (f.exists() && f.canExecute()) return candidate
        }
        return null
    }

    suspend fun probe(force: Boolean = false): RootStatus = withContext(Dispatchers.IO) {
        val current = _status.value
        if (!force && current.state == RootProbeState.GRANTED) return@withContext current

        _status.value = current.copy(state = RootProbeState.PROBING)
        val binary = findSuBinary() ?: run {
            val st = RootStatus(RootProbeState.NO_SU, null, "No su binary found on this device.")
            _status.value = st
            return@withContext st
        }

        val st = runCatching {
            val proc = ProcessBuilder(binary, "-c", "id").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val finished = proc.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                RootStatus(RootProbeState.DENIED, binary, "Root prompt timed out.")
            } else if (proc.exitValue() == 0 && out.contains("uid=0")) {
                RootStatus(RootProbeState.GRANTED, binary, out)
            } else if (binary == "su" && out.contains("not found", ignoreCase = true)) {
                RootStatus(RootProbeState.NO_SU, null, "No su binary found on PATH.")
            } else {
                RootStatus(RootProbeState.DENIED, binary, out.ifBlank { "Root access denied (exit ${proc.exitValue()})." })
            }
        }.getOrElse { e ->
            Log.w(TAG, "Root probe failed: ${e.message}")
            val state = if (e.message?.contains("No such file", ignoreCase = true) == true) RootProbeState.NO_SU else RootProbeState.DENIED
            RootStatus(state, null, e.message ?: "Unable to execute su")
        }

        _status.value = st
        st
    }

    fun notReadyMessage(): String? {
        val s = _status.value
        return when (s.state) {
            RootProbeState.GRANTED -> null
            RootProbeState.NO_SU -> "Root (`su`) was not found on this device."
            RootProbeState.DENIED -> "Root permission was denied. Grant root to CineCode-AI in Magisk / KernelSU / APatch and tap Check Root."
            RootProbeState.UNKNOWN, RootProbeState.PROBING -> "Root access has not been verified yet. Open CineCode-AI → Settings → ADB Connect → Root and tap Grant Root."
        }
    }

    suspend fun exec(
        command: String,
        timeoutMs: Long = 30_000L,
        cwd: String? = null
    ): LocalCommandResult {
        val out = ByteArrayOutputStream()
        val code = execStreaming(
            command = command,
            cwd = cwd,
            stdin = null,
            stdout = out,
            timeoutMs = timeoutMs
        )
        return LocalCommandResult(code, out.toString(Charsets.UTF_8.name()))
    }

    suspend fun execStreaming(
        command: String,
        cwd: String? = null,
        stdin: InputStream? = null,
        stdout: OutputStream,
        timeoutMs: Long = 30_000L
    ): Int = withContext(Dispatchers.IO) {
        val su = _status.value.suBinary ?: findSuBinary() ?: error("No su binary available")
        val fullCmd = if (!cwd.isNullOrBlank()) {
            val safeCwd = cwd.replace("'", "'\\''")
            "cd '$safeCwd' 2>/dev/null || cd /data/local/tmp 2>/dev/null || true; $command"
        } else {
            command
        }
        val process = ProcessBuilder(su, "-c", fullCmd)
            .redirectErrorStream(true)
            .start()

        val stdinThread = thread(name = "su-stdin", isDaemon = true) {
            try {
                process.outputStream.use { procIn ->
                    if (stdin != null) {
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = stdin.read(buf)
                            if (r <= 0) break
                            procIn.write(buf, 0, r)
                            procIn.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
                runCatching { process.outputStream.close() }
            }
        }

        val stdoutThread = thread(name = "su-stdout", isDaemon = true) {
            try {
                process.inputStream.use { procOut ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = procOut.read(buf)
                        if (r <= 0) break
                        synchronized(stdout) {
                            stdout.write(buf, 0, r)
                            stdout.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdinThread.interrupt()
            stdoutThread.join(500)
            return@withContext -1
        }
        stdoutThread.join(2_000)
        stdinThread.interrupt()
        process.exitValue()
    }
}
