package cn.edu.xxq.carcontrol.data

import cn.edu.xxq.carcontrol.model.DriveDirection
import cn.edu.xxq.carcontrol.model.Endpoint
import cn.edu.xxq.carcontrol.model.TransportResult
import cn.edu.xxq.carcontrol.protocol.CarProtocol
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

interface CarGateway {
    suspend fun connect(endpoint: Endpoint): TransportResult
    suspend fun button(direction: DriveDirection): TransportResult
    suspend fun joystick(x: Int, y: Int): TransportResult
    suspend fun wheels(speeds: List<Int>): TransportResult
    suspend fun photo(): TransportResult
    suspend fun recording(start: Boolean): TransportResult
    suspend fun tracking(enabled: Boolean): TransportResult
    suspend fun disconnect()
}

class TcpCarGateway : CarGateway {
    private var socket: Socket? = null

    override suspend fun connect(endpoint: Endpoint): TransportResult {
        disconnect()
        val port = endpoint.port.toIntOrNull() ?: return TransportResult(false, "TCP 端口必须是数字")
        return try {
            socket = Socket().apply { connect(InetSocketAddress(endpoint.host.trim(), port), CONNECT_TIMEOUT_MS) }
            TransportResult(true, "已连接课程 TCP ${endpoint.host}:$port")
        } catch (error: Exception) {
            disconnect()
            TransportResult(false, "TCP 连接失败：${error.message ?: "未知错误"}")
        }
    }

    override suspend fun button(direction: DriveDirection) = send(CarProtocol.button(direction), direction.label)
    override suspend fun joystick(x: Int, y: Int) = send(CarProtocol.joystick(x, y), "摇杆 x=$x, y=$y")
    override suspend fun wheels(speeds: List<Int>) = send(
        CarProtocol.wheels(speeds[0], speeds[1], speeds[2], speeds[3]),
        "四轮速度 ${speeds.joinToString()}"
    )
    override suspend fun photo() = send(CarProtocol.photo(), "拍照")
    override suspend fun recording(start: Boolean) = send(
        if (start) CarProtocol.startRecording() else CarProtocol.stopRecording(),
        if (start) "开始录像" else "结束录像"
    )
    override suspend fun tracking(enabled: Boolean) = send(CarProtocol.tracking(enabled), if (enabled) "启动循迹" else "停止循迹")

    override suspend fun disconnect() {
        socket?.close()
        socket = null
    }

    private fun send(frame: String, label: String): TransportResult {
        val activeSocket = socket ?: return TransportResult(false, "尚未连接课程 TCP")
        if (!activeSocket.isConnected || activeSocket.isClosed) return TransportResult(false, "课程 TCP 已断开")
        return try {
            activeSocket.getOutputStream().apply {
                write(frame.toByteArray(Charsets.US_ASCII))
                flush()
            }
            TransportResult(true, label, frame)
        } catch (error: Exception) {
            TransportResult(false, "发送失败：${error.message ?: "未知错误"}", frame)
        }
    }

    private companion object { const val CONNECT_TIMEOUT_MS = 3_000 }
}

class RosBridgeGateway : CarGateway {
    private var baseUrl = ""

    override suspend fun connect(endpoint: Endpoint): TransportResult {
        val port = endpoint.port.toIntOrNull() ?: return TransportResult(false, "ROS Bridge 端口必须是数字")
        baseUrl = "http://${endpoint.host.trim()}:$port"
        val result = request("GET", "/api/status")
        return if (result.ok) result.copy(message = "已连接 Jetson ROS Bridge ${endpoint.host}:$port") else result
    }

    override suspend fun button(direction: DriveDirection): TransportResult =
        request("POST", "/api/button", "{\"direction\":${direction.value}}", direction.label)

    override suspend fun joystick(x: Int, y: Int): TransportResult =
        request("POST", "/api/joystick", "{\"x\":${x.coerceIn(-100, 100)},\"y\":${y.coerceIn(-100, 100)}}", "摇杆 x=$x, y=$y")

    override suspend fun wheels(speeds: List<Int>) = unsupported("四轮独立速度只适用于课程 TCP 模式")
    override suspend fun photo() = unsupported("拍照命令只适用于课程 TCP 模式")
    override suspend fun recording(start: Boolean) = unsupported("录像命令只适用于课程 TCP 模式")
    override suspend fun tracking(enabled: Boolean) = unsupported("循迹命令只适用于课程 TCP 模式")

    override suspend fun disconnect() { baseUrl = "" }

    private fun unsupported(message: String) = TransportResult(false, message)

    private fun request(method: String, path: String, body: String? = null, label: String = "ROS 指令"): TransportResult {
        if (baseUrl.isBlank()) return TransportResult(false, "尚未连接 Jetson ROS Bridge")
        return try {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3_000
                readTimeout = 3_000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
                }
            }
            val response = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val success = connection.responseCode in 200..299 && !response.contains("\"error\"")
            TransportResult(success, if (success) label else "ROS Bridge 请求失败：$response", response)
        } catch (error: Exception) {
            TransportResult(false, "ROS Bridge 不可达：${error.message ?: "未知错误"}")
        }
    }
}
