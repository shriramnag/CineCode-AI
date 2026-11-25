package com.shriram.cinecode.data.adb

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Base64
import android.util.Log
import com.shriram.cinecode.data.local.LocalCommandResult
import com.shriram.cinecode.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class AccessMode(val wireName: String, val displayName: String) {
    ADB("adb", "ADB"),
    SHIZUKU("shizuku", "Shizuku"),
    ROOT("root", "Root")
}

/**
 * Unified device-level shell privilege router across Wireless ADB, Shizuku, and Root (`su`),
 * plus local HTTP execution streaming for `/api/device/exec` and `rish` / `android-su` wrappers.
 */
object DeviceAccess {
    private const val TAG = "DeviceAccess"
    private const val PREFS_NAME = "device_access_prefs"
    private const val KEY_MODE = "selected_mode"
    private val VALID_EXEC_ID = Regex("^[A-Za-z0-9._-]{1,64}$")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _selectedMode = MutableStateFlow(AccessMode.ADB)
    val selectedMode: StateFlow<AccessMode> = _selectedMode.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    private data class ExecExitRecord(
        val code: Int,
        val error: String?,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val exitStatuses = ConcurrentHashMap<String, ExecExitRecord>()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_MODE, AccessMode.ADB.name)
        _selectedMode.value = runCatching {
            AccessMode.valueOf(saved ?: AccessMode.ADB.name)
        }.getOrDefault(AccessMode.ADB)

        ShizukuShell.init(ctx)
        if (_selectedMode.value == AccessMode.ROOT || RootShell.findSuBinary() != null) {
            scope.launch { RootShell.probe(force = false) }
        }
    }

    fun setMode(mode: AccessMode) {
        _selectedMode.value = mode
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_MODE, mode.name)
            ?.apply()
        when (mode) {
            AccessMode.SHIZUKU -> ShizukuShell.refresh()
            AccessMode.ROOT -> scope.launch { RootShell.probe(force = true) }
            AccessMode.ADB -> scope.launch { AdbConnectManager.refresh(quiet = true) }
        }
    }

    fun refreshAll() {
        ShizukuShell.refresh()
        scope.launch { RootShell.probe(force = false) }
        AdbConnectManager.refresh(quiet = true)
    }

    fun activeModeLabel(): String {
        return when (_selectedMode.value) {
            AccessMode.ADB -> {
                val dev = AdbConnectManager.state.value.connectedDevice
                if (dev != null) "ADB (${dev.serial})" else "ADB (disconnected)"
            }
            AccessMode.SHIZUKU -> {
                val s = ShizukuShell.status.value
                if (s.isReady) "Shizuku (${s.uidLabel})" else "Shizuku (not ready)"
            }
            AccessMode.ROOT -> {
                val r = RootShell.status.value
                if (r.isReady) "Root (uid 0)" else "Root (not granted)"
            }
        }
    }

    fun isReady(mode: AccessMode = _selectedMode.value): Boolean = when (mode) {
        AccessMode.ADB -> AdbConnectManager.state.value.connectedDevice != null
        AccessMode.SHIZUKU -> ShizukuShell.status.value.isReady
        AccessMode.ROOT -> RootShell.status.value.isReady
    }

    suspend fun ensureReadyError(mode: AccessMode = _selectedMode.value): String? {
        return when (mode) {
            AccessMode.ADB -> {
                if (AdbConnectManager.ensureConnected() != null) null
                else "ADB is not connected. Open CineCode-AI → Settings → ADB Connect and connect (Wireless debugging), or switch to Shizuku or Root there."
            }
            AccessMode.SHIZUKU -> ShizukuShell.notReadyMessage()
            AccessMode.ROOT -> {
                if (!RootShell.status.value.isReady) RootShell.probe(force = true)
                RootShell.notReadyMessage()
            }
        }
    }

    suspend fun exec(
        command: String,
        timeoutSeconds: Int = 30,
        mode: AccessMode = _selectedMode.value,
        cwd: String? = null
    ): LocalCommandResult {
        ensureReadyError(mode)?.let { msg ->
            return LocalCommandResult(-1, msg)
        }
        return when (mode) {
            AccessMode.ADB -> {
                val dev = AdbConnectManager.ensureConnected()
                    ?: return LocalCommandResult(-1, "ADB is not connected.")
                AdbConnectManager.deviceShell(dev, command, timeoutSeconds)
            }
            AccessMode.SHIZUKU -> ShizukuShell.exec(command, timeoutSeconds * 1000L, cwd)
            AccessMode.ROOT -> RootShell.exec(command, timeoutSeconds * 1000L, cwd)
        }
    }

    suspend fun installApk(
        context: Context,
        path: String,
        mode: AccessMode = _selectedMode.value
    ): LocalCommandResult {
        if (mode == AccessMode.ADB) {
            val dev = AdbConnectManager.ensureConnected()
                ?: return LocalCommandResult(-1, "ADB is not connected.")
            return AdbConnectManager.runAdb(dev, "install -r ${AdbConnectManager.shellQuote(path)}", 240)
        }
        ensureReadyError(mode)?.let { return LocalCommandResult(-1, it) }
        val file = resolveReadableFile(context, path)
            ?: return LocalCommandResult(-1, "APK file not found or unreadable: $path")
        val cmd = "pm install -r -S ${file.length()}"
        val out = ByteArrayOutputStream()
        val code = file.inputStream().use { input ->
            when (mode) {
                AccessMode.SHIZUKU -> ShizukuShell.execStreaming(cmd, null, input, out, 240_000L)
                AccessMode.ROOT -> RootShell.execStreaming(cmd, null, input, out, 240_000L)
                AccessMode.ADB -> -1
            }
        }
        return LocalCommandResult(code, out.toString(Charsets.UTF_8.name()))
    }

    private fun resolveReadableFile(context: Context, path: String): File? {
        val raw = path.trim()
        val candidates = listOf(
            File(raw),
            File(LocalEnvironmentManager.getHomeDir(context), raw.removePrefix("~/"))
        )
        return candidates.firstOrNull { it.isFile && it.canRead() }
    }

    private fun decodeBase64(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            String(Base64.decode(value.trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun recordExit(id: String, code: Int, error: String? = null) {
        val now = System.currentTimeMillis()
        exitStatuses.entries.removeIf { now - it.value.createdAtMs > 120_000L }
        exitStatuses[id] = ExecExitRecord(code, error, now)
    }

    private fun writeSimpleHttpResponse(out: OutputStream, status: Int, body: String) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    suspend fun handleExecHttp(
        headers: Map<String, String>,
        bodyBytes: ByteArray,
        out: OutputStream
    ) {
        val execId = headers["x-exec-id"]?.trim().orEmpty()
        if (!VALID_EXEC_ID.matches(execId)) {
            writeSimpleHttpResponse(out, 400, "Missing or invalid X-Exec-Id")
            return
        }
        val mode = when (headers["x-exec-mode"]?.trim()?.lowercase()) {
            "shizuku" -> AccessMode.SHIZUKU
            "root" -> AccessMode.ROOT
            else -> {
                recordExit(execId, 2, "X-Exec-Mode must be shizuku or root")
                writeSimpleHttpResponse(out, 400, "X-Exec-Mode must be shizuku or root")
                return
            }
        }
        val cmd = decodeBase64(headers["x-exec-command"])?.trim()
        if (cmd.isNullOrEmpty()) {
            recordExit(execId, 2, "No command given")
            writeSimpleHttpResponse(out, 400, "No command")
            return
        }

        val notReady = ensureReadyError(mode)
        if (notReady != null) {
            recordExit(execId, 1, notReady)
            writeSimpleHttpResponse(out, 409, notReady)
            return
        }

        val cwd = decodeBase64(headers["x-exec-cwd"])
        val responseHead = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(responseHead.toByteArray(Charsets.US_ASCII))
        out.flush()

        val finished = AtomicBoolean(false)
        try {
            val stdin = if (bodyBytes.isNotEmpty()) bodyBytes.inputStream() else null
            val code = when (mode) {
                AccessMode.SHIZUKU -> ShizukuShell.execStreaming(cmd, cwd, stdin, out, 86_400_000L)
                AccessMode.ROOT -> RootShell.execStreaming(cmd, cwd, stdin, out, 86_400_000L)
                AccessMode.ADB -> 1
            }
            finished.set(true)
            recordExit(execId, code, null)
        } catch (e: Exception) {
            if (!finished.get()) {
                recordExit(execId, 1, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun handleExitHttp(queryString: String, out: OutputStream) {
        val id = queryString.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.firstOrNull() == "id" }
            ?.getOrNull(1)
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            .orEmpty()

        val rec = if (id.isNotBlank()) exitStatuses.remove(id) else null
        if (rec == null) {
            writeSimpleHttpResponse(out, 404, "missing\n")
            return
        }
        val payload = if (rec.error.isNullOrBlank()) "${rec.code}" else "${rec.code}\n${rec.error}"
        writeSimpleHttpResponse(out, 200, payload)
    }

    fun writeConfigAndWrappers(context: Context, port: Int, token: String) {
        if (port <= 0 || token.isBlank()) return
        val appCtx = context.applicationContext
        val homeDir = LocalEnvironmentManager.getHomeDir(appCtx)
        val configBody = "PORT=$port\nTOKEN=$token\n"

        // Write both CineCode and AntiGem device-access paths so any script/tool finds PORT+TOKEN
        listOf(
            File(homeDir, ".config/cinecode/device-access"),
            File(homeDir, ".config/antigem/device-access")
        ).forEach { cfg ->
            runCatching {
                cfg.parentFile?.mkdirs()
                cfg.writeText(configBody, Charsets.UTF_8)
                Os.chmod(cfg.absolutePath, 384) // 0600
            }
        }

        val rishScript = buildWrapperScript("rish", "shizuku", "shell (via Shizuku)")
        val suScript = buildWrapperScript("android-su", "root", "root (via su)")

        val targetDirs = mutableListOf(
            LocalEnvironmentManager.getBinDir(appCtx),
            File(appCtx.filesDir, "usr/local/bin")
        )
        val ubuntuLocalBin = File(LocalEnvironmentManager.getUbuntuRootDir(appCtx), "usr/local/bin")
        if (ubuntuLocalBin.parentFile?.parentFile?.isDirectory == true) {
            targetDirs.add(ubuntuLocalBin)
        }

        targetDirs.forEach { dir ->
            runCatching {
                if (dir.exists() || dir.parentFile?.exists() == true) {
                    dir.mkdirs()
                    writeExecutable(File(dir, "rish"), rishScript)
                    writeExecutable(File(dir, "android-su"), suScript)
                }
            }.onFailure {
                Log.w(TAG, "Failed writing wrappers in ${dir.absolutePath}: ${it.message}")
            }
        }
    }

    private fun writeExecutable(target: File, content: String) {
        if (target.isFile && runCatching { target.readText() == content }.getOrDefault(false)) {
            runCatching { Os.chmod(target.absolutePath, 493) }
            return
        }
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        runCatching { Os.chmod(tmp.absolutePath, 493) }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        runCatching { Os.chmod(target.absolutePath, 493) }
    }

    private fun buildWrapperScript(name: String, mode: String, roleLabel: String): String {
        return """
            |#!/bin/sh
            |NAME="$name"
            |MODE="$mode"
            |CONF="${'$'}HOME/.config/cinecode/device-access"
            |[ -r "${'$'}CONF" ] || CONF="${'$'}HOME/.config/antigem/device-access"
            |if [ ! -r "${'$'}CONF" ]; then
            |    echo "${'$'}NAME: ${'$'}CONF is missing. Open CineCode-AI once, then try again." >&2
            |    exit 1
            |fi
            |. "${'$'}CONF"
            |if [ -z "${'$'}PORT" ] || [ -z "${'$'}TOKEN" ]; then
            |    echo "${'$'}NAME: ${'$'}CONF is incomplete. Open CineCode-AI once, then try again." >&2
            |    exit 1
            |fi
            |if ! command -v curl >/dev/null 2>&1; then
            |    echo "${'$'}NAME needs curl: install it (pkg install curl, or apt install curl)." >&2
            |    exit 1
            |fi
            |
            |usage() {
            |    cat <<USAGE
            |Usage: ${'$'}NAME -c 'command'     run a command (sh syntax) on the phone as $roleLabel
            |       ${'$'}NAME program [args]   run a program with these arguments
            |       ${'$'}NAME                  interactive: each line runs as its own command (cd is remembered)
            |USAGE
            |}
            |
            |b64() { printf '%s' "${'$'}1" | base64 | tr -d '\n'; }
            |
            |has_input() {
            |    [ -f /dev/stdin ] && return 0
            |    [ -p /dev/stdin ] || return 1
            |    local i
            |    for i in 1 2 3 4 5 6 7 8 9 10; do
            |        read -t 0 2>/dev/null && return 0
            |        sleep 0.1
            |    done
            |    return 1
            |}
            |
            |run() {
            |    local id="${'$'}{RANDOM}${'$'}{RANDOM}${'$'}${'$'}${'$'}(date +%s%N 2>/dev/null || echo 0)"
            |    local input=/dev/null
            |    if has_input; then input=/dev/stdin; fi
            |    curl -s -N --fail -X POST -H "Expect:" \
            |        -H "X-Device-Id: ${'$'}TOKEN" -H "X-Exec-Id: ${'$'}id" -H "X-Exec-Mode: ${'$'}MODE" \
            |        -H "X-Exec-Command: ${'$'}(b64 "${'$'}1")" -H "X-Exec-Cwd: ${'$'}(b64 "${'$'}2")" \
            |        -H "Content-Type: application/octet-stream" --data-binary "@${'$'}input" \
            |        "http://127.0.0.1:${'$'}PORT/api/device/exec"
            |    local rc=${'$'}?
            |    if [ ${'$'}rc -eq 7 ]; then
            |        echo "${'$'}NAME: can't reach CineCode-AI on port ${'$'}PORT. Is CineCode-AI running?" >&2
            |        return 1
            |    fi
            |    local status
            |    status=${'$'}(curl -sS -H "X-Device-Id: ${'$'}TOKEN" "http://127.0.0.1:${'$'}PORT/api/device/exit?id=${'$'}id" 2>/dev/null)
            |    local code="${'$'}{status%%${'$'}'\n'*}"
            |    if [ "${'$'}status" != "${'$'}code" ]; then echo "${'$'}NAME: ${'$'}{status#*${'$'}'\n'}" >&2; fi
            |    case "${'$'}code" in
            |        ''|*[!0-9]*) [ -z "${'$'}status" ] && echo "${'$'}NAME: no exit code from CineCode-AI (curl exit ${'$'}rc)" >&2; return 1 ;;
            |    esac
            |    return "${'$'}code"
            |}
            |
            |case "${'$'}{1:-}" in
            |    -h|--help) usage; exit 0 ;;
            |    -c)
            |        if [ ${'$'}# -lt 2 ]; then usage >&2; exit 2; fi
            |        run "${'$'}2" "${'$'}PWD"; exit ${'$'}? ;;
            |    -*) echo "${'$'}NAME: unknown option ${'$'}1" >&2; usage >&2; exit 2 ;;
            |esac
            |if [ ${'$'}# -gt 0 ]; then
            |    run "${'$'}(printf '%q ' "${'$'}@")" "${'$'}PWD"; exit ${'$'}?
            |fi
            |if [ ! -t 0 ]; then
            |    if ! has_input; then usage >&2; exit 2; fi
            |    script=${'$'}(cat)
            |    run "${'$'}script" "${'$'}PWD" < /dev/null; exit ${'$'}?
            |fi
            |cwd="${'$'}PWD"
            |echo "${'$'}NAME: commands run on the phone as $roleLabel (type 'exit' to quit)."
            |while printf '%s> ' "${'$'}cwd"; do
            |    IFS= read -r line || { echo; break; }
            |    case "${'$'}line" in
            |        '') continue ;;
            |        exit|quit) break ;;
            |        cd) cwd="/data/local/tmp"; continue ;;
            |        cd\ *) cwd="${'$'}{line#cd }"; continue ;;
            |    esac
            |    run "${'$'}line" "${'$'}cwd" < /dev/null
            |done
        """.trimMargin() + "\n"
    }
}
