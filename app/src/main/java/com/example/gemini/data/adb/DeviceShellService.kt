package com.shriram.cinecode.data.adb

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.Process
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Shizuku UserService binder executing /system/bin/sh commands with streaming stdin/stdout/stderr
 * and isolated process-group cleanup. Loaded in Shizuku's privileged process (uid 2000 or 0).
 */
@Keep
class DeviceShellService : Binder() {

    private class RunningProcess(
        val process: java.lang.Process,
        @Volatile var pid: Int = -1
    )

    private val running = ConcurrentHashMap<Int, RunningProcess>()
    private val nextId = AtomicInteger(1)

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACT_EXEC -> {
                data.enforceInterface(DESCRIPTOR)
                val cmd = data.readString().orEmpty()
                val cwd = data.readString()
                val (id, pfds) = startProcess(cmd, cwd)
                if (reply != null) {
                    reply.writeNoException()
                    reply.writeInt(id)
                    for (pfd in pfds) {
                        pfd.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                    }
                } else {
                    for (pfd in pfds) runCatching { pfd.close() }
                }
                return true
            }
            TRANSACT_KILL -> {
                data.enforceInterface(DESCRIPTOR)
                val id = data.readInt()
                running.remove(id)?.let { killProcessTree(it) }
                reply?.writeNoException()
                return true
            }
            TRANSACT_UID -> {
                data.enforceInterface(DESCRIPTOR)
                if (reply != null) {
                    reply.writeNoException()
                    reply.writeInt(Process.myUid())
                }
                return true
            }
            LAST_CALL_TRANSACTION -> {
                running.values.forEach { killProcessTree(it) }
                running.clear()
                Process.killProcess(Process.myPid())
                System.exit(0)
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    private fun startProcess(cmd: String, cwd: String?): Pair<Int, Array<ParcelFileDescriptor>> {
        val wrapped = buildString {
            appendLine("echo $$")
            if (!cwd.isNullOrBlank()) {
                val safeCwd = cwd.replace("'", "'\\''")
                appendLine("cd '$safeCwd' 2>/dev/null || cd /data/local/tmp 2>/dev/null || true")
            }
            append(cmd)
        }
        val process = ProcessBuilder("/system/bin/sh", "-c", wrapped)
            .redirectErrorStream(true)
            .start()
        val entry = RunningProcess(process)
        val id = nextId.getAndIncrement()
        running[id] = entry

        val stdinPipe = ParcelFileDescriptor.createPipe()
        val stdoutPipe = ParcelFileDescriptor.createPipe()
        val exitPipe = ParcelFileDescriptor.createPipe()

        val stdinRead = stdinPipe[0]
        val stdinWrite = stdinPipe[1]
        val stdoutRead = stdoutPipe[0]
        val stdoutWrite = stdoutPipe[1]
        val exitRead = exitPipe[0]
        val exitWrite = exitPipe[1]

        thread(name = "device-shell-in-$id", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(stdinRead).use { input ->
                    process.outputStream.use { procIn ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            procIn.write(buf, 0, r)
                            procIn.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
                runCatching { stdinRead.close() }
                runCatching { process.outputStream.close() }
            }
        }

        thread(name = "device-shell-out-$id", isDaemon = true) {
            var exitCode = -1
            try {
                val procOut = process.inputStream
                ParcelFileDescriptor.AutoCloseOutputStream(stdoutWrite).use { outStream ->
                    // First line is `echo $$` from the wrapper shell
                    val firstLine = readLineBytes(procOut)
                    if (firstLine != null) {
                        firstLine.toString(Charsets.UTF_8).trim().toIntOrNull()?.let { pid ->
                            entry.pid = pid
                        }
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = procOut.read(buf)
                            if (r <= 0) break
                            outStream.write(buf, 0, r)
                            outStream.flush()
                        }
                    }
                }
            } catch (_: Throwable) {
                runCatching { stdoutWrite.close() }
            } finally {
                exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
                running.remove(id)
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(exitWrite).use { exitOut ->
                        exitOut.write("$exitCode\n".toByteArray(Charsets.UTF_8))
                        exitOut.flush()
                    }
                }
            }
        }

        return id to arrayOf(stdinWrite, stdoutRead, exitRead)
    }

    private fun readLineBytes(input: InputStream): ByteArray? {
        val baos = ByteArrayOutputStream(32)
        while (true) {
            val b = input.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toByteArray()
            if (b == '\n'.code) return baos.toByteArray()
            baos.write(b)
            if (baos.size() > 256) return baos.toByteArray()
        }
    }

    private fun killProcessTree(entry: RunningProcess) {
        val pid = entry.pid
        if (pid > 0) {
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("/system/bin/sh", "-c", "pkill -9 -P $pid 2>/dev/null; kill -9 $pid 2>/dev/null"))
                    .waitFor()
            }
        }
        runCatching { entry.process.destroy() }
    }

    companion object {
        val DESCRIPTOR: String = DeviceShellService::class.java.name
        const val TRANSACT_EXEC = 2
        const val TRANSACT_KILL = 3
        const val TRANSACT_UID = 4
    }
}
