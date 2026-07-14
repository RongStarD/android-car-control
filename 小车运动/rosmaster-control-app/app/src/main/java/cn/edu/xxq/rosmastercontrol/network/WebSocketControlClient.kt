package cn.edu.xxq.rosmastercontrol.network

import cn.edu.xxq.rosmastercontrol.model.ControlProtocol
import cn.edu.xxq.rosmastercontrol.model.ServerMessage
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

interface ControlConnectionListener {
    fun onConnectionChanged(phase: ConnectionPhase, message: String)
    fun onServerMessage(message: ServerMessage)
}

class WebSocketControlClient(
    private val listener: ControlConnectionListener,
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var generation: Long = 0

    fun connect(host: String, port: Int) {
        val token: Long
        synchronized(lock) {
            generation += 1
            token = generation
            webSocket?.cancel()
            webSocket = null
        }

        listener.onConnectionChanged(ConnectionPhase.CONNECTING, "正在连接 $host:$port")
        val request = Request.Builder()
            .url("ws://$host:$port")
            .build()

        val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(token)) {
                    webSocket.close(1000, "stale connection")
                    return
                }
                listener.onConnectionChanged(ConnectionPhase.CONNECTED, "控制通道已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(token)) return
                try {
                    listener.onServerMessage(ControlProtocol.parse(text))
                } catch (error: Exception) {
                    listener.onConnectionChanged(
                        ConnectionPhase.CONNECTED,
                        "收到无法解析的状态：${error.message ?: "JSON 格式错误"}",
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!clearIfCurrent(token)) return
                listener.onConnectionChanged(
                    ConnectionPhase.DISCONNECTED,
                    reason.ifBlank { "连接已关闭 ($code)" },
                )
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                if (!clearIfCurrent(token)) return
                listener.onConnectionChanged(
                    ConnectionPhase.DISCONNECTED,
                    "连接失败：${throwable.message ?: "网络不可用"}",
                )
            }
        })

        synchronized(lock) {
            if (generation == token) {
                webSocket = socket
            } else {
                socket.cancel()
            }
        }
    }

    fun send(text: String): Boolean = synchronized(lock) {
        webSocket?.send(text) == true
    }

    fun disconnect(stopId: Long) {
        val socket = synchronized(lock) {
            generation += 1
            webSocket.also { webSocket = null }
        }
        if (socket != null) {
            socket.send(ControlProtocol.stop(stopId))
            socket.close(1000, "用户断开")
        }
        listener.onConnectionChanged(ConnectionPhase.DISCONNECTED, "已断开")
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lock) {
        generation == token
    }

    private fun clearIfCurrent(token: Long): Boolean = synchronized(lock) {
        if (generation != token) return@synchronized false
        webSocket = null
        true
    }
}
