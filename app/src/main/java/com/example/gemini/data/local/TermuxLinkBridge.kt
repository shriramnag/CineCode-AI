package com.shriram.cinecode.data.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Safe bridge to external Termux (`com.termux`) when Android's W^X / `noexec` security policy
 * (error=13 Permission denied) prevents direct execution of binaries inside this app's `/files/usr/bin/`.
 */
object TermuxLinkBridge {
    private const val TAG = "TermuxLinkBridge"
    const val EXTERNAL_TERMUX_PACKAGE = "com.termux"
    const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val SERVICE_RUN_COMMAND = "com.termux.app.RunCommandService"

    fun isExternalTermuxInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(EXTERNAL_TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(EXTERNAL_TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasRunCommandPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            TERMUX_RUN_COMMAND_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun openExternalTermux(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(EXTERNAL_TERMUX_PACKAGE)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open external Termux: ${e.message}")
            false
        }
    }

    /**
     * Dispatches a command to external `com.termux` if installed and RUN_COMMAND permission is granted.
     * Never throws; returns Result.failure when unavailable so callers can fall back to In-App / Cloud mode.
     */
    fun sendCommandToExternalTermux(
        context: Context,
        commandPath: String = "/data/data/com.termux/files/usr/bin/bash",
        arguments: Array<String> = emptyArray(),
        workdir: String? = null,
        background: Boolean = true
    ): Result<String> {
        if (!isExternalTermuxInstalled(context)) {
            return Result.failure(IllegalStateException("External Termux (com.termux) is not installed."))
        }
        if (!hasRunCommandPermission(context)) {
            return Result.failure(SecurityException("RUN_COMMAND permission for external Termux is not granted."))
        }
        return runCatching {
            val intent = Intent(ACTION_RUN_COMMAND).apply {
                setClassName(EXTERNAL_TERMUX_PACKAGE, SERVICE_RUN_COMMAND)
                putExtra("com.termux.RUN_COMMAND_PATH", commandPath)
                if (arguments.isNotEmpty()) {
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                }
                if (!workdir.isNullOrBlank()) {
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                }
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            "Command dispatched to external Termux."
        }.onFailure { err ->
            Log.w(TAG, "External Termux dispatch failed: ${err.message}")
        }
    }

    /**
     * Returns a concise Hindi/English explanation of active fallback modes when Android W^X blocks local bash.
     */
    fun buildWxFallbackMessage(context: Context): String {
        val termuxInstalled = isExternalTermuxInstalled(context)
        return if (termuxInstalled) {
            "Android सुरक्षा नीति (W^X / noexec, error=13) के कारण इस ऐप के अंदर लोकल bash सीधे नहीं चल सकता। " +
                "सेटअप को सुरक्षित रूप से पूरा कर दिया गया है — आप बाहरी Termux (com.termux), इन-ऐप सिस्टम शेल (/system/bin/sh), " +
                "और Google AI Studio Cloud/API मोड में बिना किसी रुकावट के काम कर सकते हैं।"
        } else {
            "Android सुरक्षा नीति (W^X / noexec, error=13) के कारण इस ऐप के अंदर लोकल bash सीधे नहीं चल सकता। " +
                "सेटअप को सुरक्षित रूप से पूरा कर दिया गया है — आप इन-ऐप सिस्टम शेल (/system/bin/sh) और " +
                "Google AI Studio Cloud/API मोड में बिना किसी रुकावट के काम कर सकते हैं (वैकल्पिक रूप से Termux भी उपयोग किया जा सकता है)।"
        }
    }
}
