package com.spacexjr.saturnbmo.net

import android.util.Log
import com.spacexjr.saturnbmo.AppSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente WebSocket do protocolo BMO/1. Autentica por cabeçalho Authorization.
 */
class BmoClient(private val listener: Listener) {
    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(bytes: ByteArray)
        fun onClosed(code: Int, reason: String)
        fun onFailure(message: String)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Volatile
    private var socket: WebSocket? = null
    private val notify = AtomicBoolean(true)

    fun connect(settings: AppSettings) {
        notify.set(true)
        val request = Request.Builder()
            .url(settings.wsUrl)
            .header("Authorization", "Bearer ${settings.token}")
            .header("X-BMO-Client", "android")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket aberto")
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("protocol", "bmo/1")
                    .put("client", "saturnBMO Android")
                    .put("client_version", BuildInfo.VERSION)
                webSocket.send(hello.toString())
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBinary(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (notify.getAndSet(false)) listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (notify.getAndSet(false)) {
                    listener.onFailure(t.message ?: "falha de rede")
                }
            }
        })
    }

    fun sendJson(payload: JSONObject): Boolean {
        return socket?.send(payload.toString()) ?: false
    }

    fun sendBytes(bytes: ByteArray): Boolean {
        return socket?.send(ByteString.of(*bytes)) ?: false
    }

    fun close() {
        notify.set(false)
        socket?.close(1000, "encerrado pelo app")
        socket = null
    }

    private object BuildInfo {
        const val VERSION = "0.1.0"
    }

    companion object {
        private const val TAG = "BmoClient"
    }
}
