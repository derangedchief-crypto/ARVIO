package com.arflix.tv.management

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.random.Random

internal data class ManagementLiveIdentity(val deviceId: String, val credential: String) {
    override fun toString() = "ManagementLiveIdentity([redacted])"
}
internal fun managementLiveIdentity(session: ManagementSession): ManagementLiveIdentity? {
    val device = session.deviceId ?: return null
    val token = session.credential ?: return null
    return ManagementLiveIdentity(device, token)
}

/** Network notifications are only hints: all state/commands still come from check-in. */
internal class ManagementLiveConnection(private val api: ManagementApi) {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val retry = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var socket: WebSocket? = null
    @Volatile var connected = false
        private set

    fun reconnect() {
        connected = false
        socket?.cancel()
        retry.trySend(Unit)
    }

    suspend fun run(identity: StateFlow<ManagementLiveIdentity?>, onWake: () -> Unit) {
        try {
            identity.collectLatest { current ->
                if (current == null) return@collectLatest
                var failures = 0
                while (currentCoroutineContext().isActive) {
                    var pause = 5_000L
                    try {
                        val response = api.control("/api/device/live-ticket", JSONObject(), current.credential)
                        if (!response.optBoolean("enabled")) {
                            pause = 60_000
                        } else {
                            require(response.getInt("schema_version") == 1 && response.getString("device_id") == current.deviceId)
                            val ticket = response.getString("ticket")
                            require(ticket.length <= 2048 && ticket.matches(Regex("[A-Za-z0-9_-]+\\.[a-f0-9]{64}")))
                            val lifetime = response.getInt("expires_in").also { require(it in 1..300) }
                            // Also bound lifetime client-side if a broken server never closes it.
                            withTimeoutOrNull((lifetime + 5) * 1000L) { listen(ticket) { failures = 0; onWake() } }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failures = (failures + 1).coerceAtMost(4)
                        pause = (5_000L shl (failures - 1)).coerceAtMost(30_000)
                    } finally {
                        connected = false
                        socket?.cancel()
                        socket = null
                    }
                    withTimeoutOrNull(pause + Random.nextLong(0, 1000)) { retry.receive() }
                }
            }
        } finally {
            connected = false
            socket?.cancel()
            socket = null
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun listen(ticket: String, onWake: () -> Unit): Unit = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url("wss://control.fecxbrfzku.net/api/device/live")
            .header("Authorization", "Bearer $ticket").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!cont.isActive) return
                try {
                    require(text.length <= 128)
                    when (JSONObject(text).getString("type")) {
                        "ready" -> { connected = true; onWake() }
                        "check_in" -> onWake()
                        else -> webSocket.close(1008, "Unsupported notification")
                    }
                } catch (_: Exception) { webSocket.close(1008, "Invalid notification") }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (cont.isActive) cont.resume(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (cont.isActive) cont.resume(Unit) }
        })
        socket = ws
        cont.invokeOnCancellation { ws.cancel() }
    }
}

/** Registered only while the app is visible; network permission failure keeps polling. */
internal fun managementNetworkWake(context: Context, wake: () -> Unit): () -> Unit {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return {}
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wake()
        override fun onLost(network: Network) = wake()
    }
    return try {
        manager.registerDefaultNetworkCallback(callback)
        val stop: () -> Unit = { runCatching { manager.unregisterNetworkCallback(callback) }; Unit }
        stop
    } catch (_: Exception) { {} }
}
