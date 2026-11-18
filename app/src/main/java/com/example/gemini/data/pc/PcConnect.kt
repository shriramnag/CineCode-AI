package com.shriram.cinecode.data.pc

import com.shriram.cinecode.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

data class PcPairingInfo(
    val phoneLanIps: List<String>,
    val bridgePort: Int = BuildConfig.BRIDGE_PORT,
    val hubPort: Int = BuildConfig.HUB_PORT,
    val adbReverseCmd: String = "adb reverse tcp:${BuildConfig.BRIDGE_PORT} tcp:${BuildConfig.BRIDGE_PORT} && adb reverse tcp:${BuildConfig.HUB_PORT} tcp:${BuildConfig.HUB_PORT}",
    val adbForwardCmd: String = "adb forward tcp:${BuildConfig.BRIDGE_PORT} tcp:${BuildConfig.BRIDGE_PORT}"
)

/**
 * Connect-to-PC helper for CineCode-AI.
 * Detects local LAN IPs, generates USB/Wi-Fi ADB & Bridge pairing commands, and tests PC IDE Bridge endpoints.
 */
object PcConnect {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun getPairingInfo(): PcPairingInfo {
        val ips = mutableListOf<String>()
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        }
        return PcPairingInfo(phoneLanIps = ips.distinct())
    }

    fun buildPcBridgeUrl(pcIp: String, port: Int = BuildConfig.BRIDGE_PORT): String {
        val clean = pcIp.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        return if (clean.contains(':')) "http://$clean" else "http://$clean:$port"
    }

    fun buildPcHubUrl(pcIp: String, port: Int = BuildConfig.HUB_PORT): String {
        val clean = pcIp.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        val host = clean.substringBefore(':')
        return "http://$host:$port"
    }

    suspend fun probePcBridge(pcIpOrUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = buildPcBridgeUrl(pcIpOrUrl)
            val req = Request.Builder().url("$base/api/health").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                "Connected to PC Bridge ($base)"
            }
        }
    }
}
