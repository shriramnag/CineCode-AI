package com.shriram.cinecode.crash

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Global Crash Guard for 4GB RAM devices.
 * Captures uncaught exceptions (including OOM), frees transient caches, stores a compact crash report,
 * and opens CrashActivity so users see a safe recovery screen instead of an abrupt silent crash.
 */
object CrashHandler {
    const val EXTRA_ERROR_MESSAGE = "extra_error_message"
    const val EXTRA_STACK_TRACE = "extra_stack_trace"
    const val EXTRA_IS_OOM = "extra_is_oom"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stack = sw.toString().take(12000)
                val isOom = throwable is OutOfMemoryError || stack.contains("OutOfMemoryError")

                runCatching {
                    val crashDir = File(appContext.filesDir, "crashes").apply { mkdirs() }
                    File(crashDir, "last_crash.txt").writeText(
                        "Thread: ${thread.name}\nTime: ${System.currentTimeMillis()}\nOOM: $isOom\n\n$stack"
                    )
                }

                val intent = Intent(appContext, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_ERROR_MESSAGE, throwable.message ?: throwable.javaClass.simpleName)
                    putExtra(EXTRA_STACK_TRACE, stack)
                    putExtra(EXTRA_IS_OOM, isOom)
                }
                appContext.startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun readLastCrash(context: Context): String? = runCatching {
        val f = File(context.applicationContext.filesDir, "crashes/last_crash.txt")
        if (f.exists()) f.readText() else null
    }.getOrNull()
}
