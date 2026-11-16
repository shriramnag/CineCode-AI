package com.shriram.cinecode.ui.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * CineCodeBrowserManager: Real-time Chrome Custom Tabs (CCT) bridge for secure OAuth,
 * Google Sign-In, and external authentication links.
 * Avoids insecure WebView sign-in blocks while preserving dark-space CineCode-AI styling.
 */
object CineCodeBrowserManager {

    private const val TAG = "CineCodeBrowserManager"
    private const val DARK_SPACE_TOOLBAR_COLOR = 0xFF0D1117.toInt()

    @JvmStatic
    fun launchRealBrowser(context: Context, url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return

        val uri = runCatching { Uri.parse(trimmedUrl) }.getOrNull() ?: return

        try {
            val darkColorParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(DARK_SPACE_TOOLBAR_COLOR)
                .setNavigationBarColor(DARK_SPACE_TOOLBAR_COLOR)
                .build()

            val customTabsIntent = CustomTabsIntent.Builder()
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setDefaultColorSchemeParams(darkColorParams)
                .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, darkColorParams)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                .build()

            if (context !is Activity) {
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            customTabsIntent.launchUrl(context, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Chrome Custom Tabs unavailable, falling back to external browser: ${e.message}")
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(fallbackIntent)
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Failed to open external browser for url=$trimmedUrl", fallbackError)
            }
        }
    }
}
