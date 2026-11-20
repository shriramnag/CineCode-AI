package com.shriram.cinecode.data.remote.services

import com.shriram.cinecode.data.preferences.AuthPreferences
import com.shriram.cinecode.data.remote.core.AgyCsrfManager
import com.shriram.cinecode.data.remote.core.AgyOkHttpClient
import com.squareup.wire.GrpcClient
import exa.language_server_pb.GrpcLanguageServerServiceClient
import exa.language_server_pb.LanguageServerServiceClient

import com.squareup.wire.GrpcCall
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import com.squareup.wire.GrpcStreamingCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

private val agyLanguageServiceLock = Any()
@Volatile
private var cachedHubUrl: String = ""
@Volatile
private var cachedLanguageService: LanguageServerServiceClient? = null

private fun normalizeHubUrl(rawUrl: String): String {
    val hubUrl = rawUrl.ifBlank { AuthPreferences.DEFAULT_HUB_URL }.trim()
    return if (!hubUrl.startsWith("http://") && !hubUrl.startsWith("https://")) {
        "http://$hubUrl"
    } else {
        hubUrl
    }.trimEnd('/')
}

/**
 * Returns a typed Antigravity LanguageServerService gRPC client bound to [hubUrl].
 * Automatically updates whenever the active local/remote Hub URL changes.
 */
fun getAgyLanguageService(hubUrl: String = AuthPreferences.currentHubUrl): LanguageServerServiceClient {
    val normalizedUrl = normalizeHubUrl(hubUrl)
    val existing = cachedLanguageService
    if (existing != null && cachedHubUrl == normalizedUrl) {
        return existing
    }
    return synchronized(agyLanguageServiceLock) {
        val current = cachedLanguageService
        if (current != null && cachedHubUrl == normalizedUrl) {
            current
        } else {
            val created = GrpcLanguageServerServiceClient(
                GrpcClient.Builder()
                    .client(AgyOkHttpClient.client)
                    .baseUrl(normalizedUrl)
                    .build()
            )
            cachedHubUrl = normalizedUrl
            cachedLanguageService = created
            created
        }
    }
}

/**
 * Single shared typed entrypoint for Antigravity LanguageServerService gRPC APIs.
 * Dynamically resolves to the currently active Hub URL using the shared OkHttpClient pool.
 */
val AgyLanguageService: LanguageServerServiceClient
    get() = getAgyLanguageService(AuthPreferences.currentHubUrl)

/**
 * Domain error model for AGY Language Server gRPC calls.
 */
sealed class AgyRpcError(override val message: String, override val cause: Throwable? = null) : Exception(message, cause) {
    data class DaemonOffline(
        override val message: String = "AGY Hub is offline or unreachable.",
        override val cause: Throwable? = null
    ) : AgyRpcError(message, cause)

    data class Unauthenticated(
        override val message: String = "Session unauthenticated or CSRF validation failed."
    ) : AgyRpcError(message)

    data class ServerError(
        val status: GrpcStatus,
        override val message: String
    ) : AgyRpcError(message)

    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : AgyRpcError(message, cause)
}

/**
 * Executes a unary gRPC call safely, mapping network/gRPC failures into AgyRpcError.
 * Automatically handles CSRF expiry by invalidating and refreshing the cached token without retrying the request.
 */
suspend fun <Req : Any, Resp : Any> GrpcCall<Req, Resp>.executeSafely(
    request: Req
): Result<Resp> {
    // the token this call goes out with: a rejection only drops the cached token if it is still this one
    val tokenAtStart = AgyCsrfManager.instance.token
    return try {
    val reqStr = request.toString().take(120)
    val res = execute(request)
    Result.success(res)
} catch (e: CancellationException) {
    android.util.Log.w("AGY_RPC", "⚠️ [AgyLanguageService.executeSafely] RPC Cancelled: ${e.message}")
    throw e
} catch (e: GrpcException) {
    val isCsrfError = e.grpcStatus == GrpcStatus.UNAUTHENTICATED || e.grpcMessage?.contains("CSRF", ignoreCase = true) == true
    val isNotLoggedIn = e.grpcMessage?.contains("not logged into", ignoreCase = true) == true ||
        e.grpcMessage?.contains("error getting token source", ignoreCase = true) == true
    if (isNotLoggedIn) {
        android.util.Log.w("AGY_RPC", "⚠️ [AgyLanguageService.executeSafely] Antigravity auth required: ${e.grpcMessage}")
    } else {
        android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] GrpcException: status=${e.grpcStatus}, msg=${e.grpcMessage}", e)
    }
    if (isCsrfError) {
        val hubUrl = AuthPreferences.currentHubUrl
        android.util.Log.w("AGY_RPC", "🔄 [AgyLanguageService.executeSafely] CSRF error detected on RPC. Invalidating token...")
        AgyCsrfManager.instance.notifyCsrfExpired(hubUrl, "executeSafely", usedToken = tokenAtStart)
    }
    val err = when {
        isCsrfError || isNotLoggedIn || e.grpcStatus == GrpcStatus.UNAUTHENTICATED ->
            AgyRpcError.Unauthenticated(e.grpcMessage ?: "Session unauthenticated or CSRF validation failed.")
        e.grpcStatus == GrpcStatus.UNAVAILABLE -> AgyRpcError.DaemonOffline("AGY Hub is offline or unreachable.", e)
        else -> AgyRpcError.ServerError(e.grpcStatus, e.grpcMessage ?: e.message ?: "gRPC error")
    }
    Result.failure(err)
} catch (e: IOException) {
    val isOfflineRefused = e is java.net.ConnectException ||
        e.cause is java.net.ConnectException ||
        (e.message?.contains("Failed to connect", ignoreCase = true) == true)
    if (isOfflineRefused) {
        android.util.Log.w("AGY_RPC", "⚠️ [AgyLanguageService.executeSafely] AGY Hub offline (${e.message})")
    } else {
        android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] IOException: ${e.message}", e)
    }
    Result.failure(AgyRpcError.DaemonOffline("Failed to connect to AGY Hub: ${e.message}", e))
} catch (e: Throwable) {
    android.util.Log.e("AGY_RPC", "❌ [AgyLanguageService.executeSafely] Throwable: ${e.message}", e)
    Result.failure(AgyRpcError.Unknown(e.message ?: "Unexpected error", e))
}
}

/**
 * Executes a server-streaming gRPC call as a Kotlin Flow with automatic error mapping.
 * On CSRF / UNAUTHENTICATED error, invalidates CSRF token cache so immediate stream reconnects succeed.
 */
fun <Req : Any, Resp : Any> GrpcStreamingCall<Req, Resp>.asFlowSafely(
    request: Req
): Flow<Resp> = callbackFlow {
    val tokenAtStart = AgyCsrfManager.instance.token
    val reqStr = request.toString().take(120)
    val (requestChannel, responseChannel) = executeIn(this)
    try {
        requestChannel.send(request)
        requestChannel.close()

        for (message in responseChannel) {
            trySend(message)
        }
        channel.close()
    } catch (e: CancellationException) {
        android.util.Log.d("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] Stream cancelled: ${e.message}")
        throw e
    } catch (e: GrpcException) {
        val isCsrfError = e.grpcStatus == GrpcStatus.UNAUTHENTICATED || e.grpcMessage?.contains("CSRF", ignoreCase = true) == true
        val isNotLoggedIn = e.grpcMessage?.contains("not logged into", ignoreCase = true) == true ||
            e.grpcMessage?.contains("error getting token source", ignoreCase = true) == true
        if (isNotLoggedIn) {
            android.util.Log.w("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] Antigravity auth required: ${e.grpcMessage}")
        } else {
            android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] GrpcException: status=${e.grpcStatus}, msg=${e.grpcMessage}", e)
        }
        if (isCsrfError) {
            val hubUrl = AuthPreferences.currentHubUrl
            android.util.Log.w("CHAT_OPEN_DEBUG", "🔄 [AgyLanguageService.asFlowSafely] CSRF error on stream. Invalidating CSRF cache for $hubUrl...")
            AgyCsrfManager.instance.notifyCsrfExpired(hubUrl, "asFlowSafely", usedToken = tokenAtStart)
        }
        val err = when {
            isCsrfError || isNotLoggedIn || e.grpcStatus == GrpcStatus.UNAUTHENTICATED ->
                AgyRpcError.Unauthenticated(e.grpcMessage ?: "Unauthenticated")
            e.grpcStatus == GrpcStatus.UNAVAILABLE -> AgyRpcError.DaemonOffline("AGY Hub is offline or unreachable.", e)
            else -> AgyRpcError.ServerError(e.grpcStatus, e.grpcMessage ?: e.message ?: "Stream error")
        }
        channel.close(err)
    } catch (e: IOException) {
        val isOfflineRefused = e is java.net.ConnectException ||
            e.cause is java.net.ConnectException ||
            (e.message?.contains("Failed to connect", ignoreCase = true) == true)
        val isHttp200StreamEof = e.message?.contains("HTTP status=200", ignoreCase = true) == true &&
            e.message?.contains("grpc-status=null", ignoreCase = true) == true
        if (isClosedForSend) {
            android.util.Log.d("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] Stream closed after cancellation: ${e.message}")
            channel.close()
        } else if (isOfflineRefused) {
            android.util.Log.w("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] AGY Hub offline (${e.message})")
            channel.close(AgyRpcError.DaemonOffline("Stream connection broken: ${e.message}", e))
        } else if (isHttp200StreamEof) {
            android.util.Log.w("CHAT_OPEN_DEBUG", "⚠️ [AgyLanguageService.asFlowSafely] Stream ended without gRPC trailers (${e.message}); will reconnect.")
            channel.close(AgyRpcError.DaemonOffline("Stream connection broken: ${e.message}", e))
        } else {
            android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] IOException: ${e.message}", e)
            channel.close(AgyRpcError.DaemonOffline("Stream connection broken: ${e.message}", e))
        }
    } catch (e: Throwable) {
        android.util.Log.e("CHAT_OPEN_DEBUG", "❌ [AgyLanguageService.asFlowSafely] Throwable: ${e.message}", e)
        channel.close(AgyRpcError.Unknown(e.message ?: "Stream error", e))
    } finally {
        responseChannel.cancel()
        requestChannel.close()
    }

    awaitClose {
        android.util.Log.d("CHAT_OPEN_DEBUG", "🔒 [AgyLanguageService.asFlowSafely] awaitClose invoked, cancelling channel.")
        responseChannel.cancel()
        requestChannel.close()
        cancel()
    }
}.flowOn(Dispatchers.IO)


