package com.shriram.cinecode

import android.app.Application
import com.shriram.cinecode.data.adb.AdbConnectManager
import com.shriram.cinecode.data.context.GlobalContext
import com.shriram.cinecode.data.updater.PluginUpdateCheckWorker
import com.shriram.cinecode.data.updater.UpdateCheckWorker
import com.shriram.cinecode.crash.CrashHandler
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class AntiGemApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Images that live on the bridge (a server's files, attachments) need the security header, which Coil's
     * default client does not send. Only requests to the bridge address get it.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bridge = com.shriram.cinecode.data.preferences.AuthPreferences.currentBridgeHttpUrl.toHttpUrlOrNull()
                val sameServer = bridge != null && req.url.host == bridge.host && req.url.port == bridge.port
                chain.proceed(
                    if (sameServer) req.newBuilder()
                        .header(com.shriram.cinecode.data.preferences.AuthPreferences.currentFramedHeader, "true")
                        .apply { com.shriram.cinecode.data.preferences.AuthPreferences.edgeHeaders().forEach { (n, v) -> header(n, v) } }
                        .build()
                    else req
                )
            }
            .build()
        return coil.ImageLoader.Builder(this).okHttpClient(client).build()
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        GlobalContext.init(this)
        // loads the saved server addresses, Remote mode and the SSH key into memory before anything connects
        com.shriram.cinecode.data.preferences.AuthPreferences(this)
        // the chosen light / dark themes and the custom ones, before the first screen draws
        com.shriram.cinecode.theme.ThemeManager.init(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        com.shriram.cinecode.data.ssh.SshSessionFactory.init(this)
        // Ensure local binaries (login, bash, node, bridge) hold 0755 execution permissions on startup
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.shriram.cinecode.data.local.LocalEnvironmentManager.ensureExecutablesPermissions(this@AntiGemApplication)
            }
        }
    }
}
