package com.shriram.cinecode.data.miniapps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject

/**
 * Native JS bridge exposed as window.__CineCodeMiniAppNative inside MiniApp WebViews.
 * Scoped per mini-app ID using lightweight SharedPreferences storage (4GB RAM safe).
 */
class MiniAppRuntime(
    private val context: Context,
    private val miniAppId: String
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("cinecode_miniapp_$miniAppId", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun storageGet(key: String): String {
        return prefs.getString(key, "") ?: ""
    }

    @JavascriptInterface
    fun storageSet(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun storageRemove(key: String) {
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        mainHandler.post {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("CineCode-AI MiniApp", text))
        }
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        return JSONObject().apply {
            put("app", "CineCode-AI")
            put("miniAppId", miniAppId)
            put("sdkVersion", "1.0.0")
        }.toString()
    }
}
