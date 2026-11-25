package com.shriram.cinecode.data.adb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import com.shriram.cinecode.data.local.LocalCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

data class ShizukuStatus(
    val installed: Boolean = false,
    val running: Boolean = false,
    val tooOld: Boolean = false,
    val granted: Boolean = false,
    val denied: Boolean = false,
    val uid: Int = -1,
    val version: Int = 0
) {
    val isReady: Boolean get() = running && !tooOld && granted
    val uidLabel: String
        get() = when (uid) {
            -1 -> "unknown"
            0 -> "root"
            2000 -> "shell"
            else -> "uid $uid"
        }
}

/**
 * Manages Shizuku permissions and executes privileged shell commands via [DeviceShellService].
 */
object ShizukuShell {
    private const val TAG = "ShizukuShell"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 4201

    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var initialized = false
    @Volatile
    private var serviceBinder: IBinder? = null

    private val bindMutex = Mutex()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!initialized) {
            initialized = true
            runCatching {
                Shizuku.addBinderReceivedListenerSticky { refresh() }
                Shizuku.addBinderDeadListener {
                    serviceBinder = null
                    refresh()
                }
                Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                    if (requestCode == PERMISSION_REQUEST_CODE) {
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        refresh(explicitDenied = !granted)
                    }
                }
            }.onFailure {
                Log.w(TAG, "Shizuku listener init failed: ${it.message}")
            }
        }
        refresh()
    }

    fun refresh(explicitDenied: Boolean? = null) {
        val ctx = appContext ?: return
        val installed = runCatching {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val tooOld = if (running) runCatching { Shizuku.isPreV11() }.getOrDefault(false) else false
        val version = if (running && !tooOld) runCatching { Shizuku.getVersion() }.getOrDefault(0) else 0

        val granted = if (running && !tooOld) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        } else {
            false
        }
        val rationale = if (running && !tooOld && !granted) {
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        } else {
            false
        }
        val uid = if (granted) {
            runCatching { Shizuku.getUid() }.getOrDefault(-1)
        } else {
            -1
        }

        _status.value = ShizukuStatus(
            installed = installed,
            running = running,
            tooOld = tooOld,
            granted = granted,
            denied = explicitDenied ?: (!_status.value.granted && rationale),
            uid = uid,
            version = version
        )
    }

    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }
    }

    fun openShizukuApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
        runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun notReadyMessage(): String? {
        refresh()
        val s = _status.value
        return when {
            !s.installed -> "Shizuku is not installed. Install it (https://shizuku.rikka.app/download/), start it, then grant CineCode-AI in Settings → ADB Connect → Shizuku."
            !s.running -> "Shizuku is not running. Open the Shizuku app and start it, then try again."
            s.tooOld -> "This Shizuku version is too old (before v11). Update Shizuku and try again."
            !s.granted -> "CineCode-AI has no Shizuku permission yet. Open CineCode-AI → Settings → ADB Connect → Shizuku and tap Grant."
            else -> null
        }
    }

    private fun buildServiceArgs(ctx: Context): Shizuku.UserServiceArgs {
        val lastUpdate = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        }.getOrDefault(1L)
        val versionCode = ((lastUpdate / 1000L) % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        return Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, DeviceShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("device_shell")
            .tag("device_shell")
            .debuggable(false)
            .version(versionCode)
    }

    private suspend fun getOrBindService(): IBinder = bindMutex.withLock {
        val existing = serviceBinder
        if (existing != null && existing.pingBinder()) return@withLock existing

        val ctx = appContext ?: error("ShizukuShell not initialized")
        notReadyMessage()?.let { error(it) }

        val deferred = CompletableDeferred<IBinder?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && service.pingBinder()) {
                    serviceBinder = service
                    deferred.complete(service)
                } else {
                    deferred.complete(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBinder = null
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }

        withContext(Dispatchers.Main) {
            Shizuku.bindUserService(buildServiceArgs(ctx), conn)
        }

        val bound = withTimeoutOrNull(20_000L) { deferred.await() }
        if (bound == null || !bound.pingBinder()) {
            error("Shizuku didn't start CineCode-AI's shell helper within 20 seconds. Restart Shizuku and try again.")
        }
        bound
    }

    suspend fun exec(
        command: String,
        timeoutMs: Long = 30_000L,
        cwd: String? = null
    ): LocalCommandResult {
        val out = java.io.ByteArrayOutputStream()
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
        val binder = getOrBindService()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var execId = -1
        var stdinPfd: ParcelFileDescriptor? = null
        var stdoutPfd: ParcelFileDescriptor? = null
        var exitPfd: ParcelFileDescriptor? = null
        try {
            data.writeInterfaceToken(DeviceShellService.DESCRIPTOR)
            data.writeString(command)
            data.writeString(cwd)
            if (!binder.transact(DeviceShellService.TRANSACT_EXEC, data, reply, 0)) {
                error("Shizuku shell transaction failed")
            }
            reply.readException()
            execId = reply.readInt()
            stdinPfd = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            stdoutPfd = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            exitPfd = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }

        val stdinThread = thread(name = "rish-stdin-$execId", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(stdinPfd).use { pipeOut ->
                    if (stdin != null) {
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = stdin.read(buf)
                            if (r <= 0) break
                            pipeOut.write(buf, 0, r)
                            pipeOut.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
                runCatching { stdinPfd?.close() }
            }
        }

        val stdoutThread = thread(name = "rish-stdout-$execId", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(stdoutPfd).use { pipeIn ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = pipeIn.read(buf)
                        if (r <= 0) break
                        synchronized(stdout) {
                            stdout.write(buf, 0, r)
                            stdout.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
                runCatching { stdoutPfd?.close() }
            }
        }

        val exitDeferred = CompletableDeferred<Int>()
        thread(name = "rish-exit-$execId", isDaemon = true) {
            val code = try {
                ParcelFileDescriptor.AutoCloseInputStream(exitPfd).bufferedReader().use { r ->
                    r.readLine()?.trim()?.toIntOrNull() ?: -1
                }
            } catch (_: Throwable) {
                -1
            }
            exitDeferred.complete(code)
        }

        val code = withTimeoutOrNull(timeoutMs) { exitDeferred.await() }
        if (code == null) {
            killRemote(binder, execId)
            stdinThread.join(300)
            stdoutThread.join(500)
            return@withContext -1
        }
        stdoutThread.join(2_000)
        stdinThread.interrupt()
        code
    }

    private fun killRemote(binder: IBinder, execId: Int) {
        if (execId <= 0) return
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(DeviceShellService.DESCRIPTOR)
            d.writeInt(execId)
            binder.transact(DeviceShellService.TRANSACT_KILL, d, r, 0)
            r.readException()
        } catch (_: Throwable) {
        } finally {
            d.recycle()
            r.recycle()
        }
    }
}
