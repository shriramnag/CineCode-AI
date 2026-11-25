package com.shriram.cinecode.data.local

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class ChrootExecResult(
    val exitCode: Int,
    val output: String,
    val lines: List<String> = emptyList()
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * Manages the bundled chrootctl.sh helper for rooted Linux chroots (/data/local/antigem).
 */
object ChrootManager {
    private val UNSAFE_ARG = Regex("['\"\\\\\$`;&|\\r\\n]")

    fun ensureScript(context: Context): File {
        val appCtx = context.applicationContext
        val dir = File(appCtx.filesDir, "chroot")
        dir.mkdirs()
        val scriptFile = File(dir, "chrootctl.sh")
        val assetBytes = appCtx.assets.open("chroot/chrootctl.sh").use { it.readBytes() }
        if (!scriptFile.exists() || !scriptFile.readBytes().contentEquals(assetBytes)) {
            val tmp = File(dir, "chrootctl.sh.tmp")
            tmp.writeBytes(assetBytes)
            tmp.setReadable(true, false)
            if (!tmp.renameTo(scriptFile)) {
                scriptFile.delete()
                tmp.renameTo(scriptFile)
            }
            scriptFile.setReadable(true, false)
        }
        return scriptFile
    }

    fun buildShellCommand(context: Context, args: List<String>): String? {
        for (arg in args) {
            if (UNSAFE_ARG.containsMatchIn(arg)) return null
        }
        val script = ensureScript(context)
        return "sh ${script.absolutePath} ${args.joinToString(" ")}"
    }

    fun encodeBase64(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0 && out == "0"
        }.getOrDefault(false)
    }

    suspend fun runCtl(
        context: Context,
        args: List<String>,
        timeoutMs: Long = 120_000L
    ): ChrootExecResult = withContext(Dispatchers.IO) {
        val cmd = buildShellCommand(context, args)
            ?: return@withContext ChrootExecResult(-1, "Unsafe argument passed to chrootctl.sh")
        runCatching {
            val proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val outputLines = mutableListOf<String>()
            val readerThread = Thread {
                proc.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { synchronized(outputLines) { outputLines.add(it) } }
                }
            }.apply { start() }

            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                readerThread.join(1000)
                return@withContext ChrootExecResult(-1, "Timed out running chrootctl", outputLines)
            }
            readerThread.join(2000)
            val lines = synchronized(outputLines) { outputLines.toList() }
            ChrootExecResult(proc.exitValue(), lines.joinToString("\n"), lines)
        }.getOrElse { e ->
            ChrootExecResult(-1, e.message ?: "Failed to execute su chrootctl")
        }
    }

    suspend fun execInChroot(
        context: Context,
        rootfsPath: String,
        command: String,
        asRoot: Boolean = true,
        timeoutMs: Long = 3_600_000L
    ): ChrootExecResult {
        val args = mutableListOf("exec", "--root", rootfsPath)
        if (asRoot) args.add("--as-root")
        args.addAll(listOf("--term", "dumb", "--cmd", encodeBase64(command)))
        return runCtl(context, args, timeoutMs)
    }
}
