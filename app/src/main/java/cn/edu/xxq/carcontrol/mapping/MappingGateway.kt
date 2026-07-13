package cn.edu.xxq.carcontrol.mapping

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.DataFormatException
import java.util.zip.Inflater

data class MappingResult(val ok: Boolean, val message: String)

/** WebSocket client for the mapping-only Jetson bridge. */
class MappingGateway {
    private val _state = MutableStateFlow(MappingState())
    val state: StateFlow<MappingState> = _state.asStateFlow()
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<MappingResult>>()
    private var socket: WebSocket? = null
    private var ready: CompletableDeferred<MappingResult>? = null

    suspend fun connect(endpoint: MappingEndpoint): MappingResult {
        disconnect()
        val port = endpoint.port.toIntOrNull() ?: return MappingResult(false, "端口必须是数字")
        _state.value = _state.value.copy(endpoint = endpoint, connecting = true, connected = false, feedback = "正在连接 Jetson…")
        val connection = CompletableDeferred<MappingResult>()
        ready = connection
        socket = client.newWebSocket(Request.Builder().url("ws://${endpoint.host.trim()}:$port").build(), listener())
        return withTimeoutOrNull(5_000) { connection.await() }
            ?: MappingResult(false, "连接超时：${endpoint.host}:$port")
    }

    suspend fun start() = command("start_mapping", "已请求启动 GMapping")
    suspend fun save() = command("save_map", "已请求保存地图")
    suspend fun stop() = command("stop_mapping", "已请求结束建图")

    fun disconnect() {
        socket?.close(1000, "App disconnected")
        socket = null
        ready?.cancel()
        ready = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        _state.value = _state.value.copy(connecting = false, connected = false, mappingActive = false)
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = _state.value.copy(connecting = false, connected = true, feedback = "已连接 Jetson 建图服务")
            ready?.complete(MappingResult(true, "已连接 Jetson 建图服务"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleText(JSONObject(text)) }
                .onFailure { _state.value = _state.value.copy(feedback = "状态数据无效：${it.message}") }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            runCatching { handleMap(bytes.toByteArray()) }
                .onFailure { _state.value = _state.value.copy(feedback = "地图数据无效：${it.message}") }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            val message = "连接失败：${throwable.message ?: "未知错误"}"
            _state.value = _state.value.copy(connecting = false, connected = false, feedback = message)
            ready?.complete(MappingResult(false, message))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = _state.value.copy(connecting = false, connected = false, mappingActive = false, feedback = "连接已关闭")
        }
    }

    private fun handleText(message: JSONObject) {
        when (message.optString("op")) {
            "state" -> _state.value = _state.value.copy(
                mappingActive = message.optBoolean("mapping_active", _state.value.mappingActive),
                scanSamples = message.optInt("scan_samples", _state.value.scanSamples),
                feedback = message.optString("detail", _state.value.feedback)
            )
            "response" -> pending.remove(message.optString("id"))?.complete(
                MappingResult(message.optBoolean("ok"), message.optString("message", "命令已完成"))
            )
        }
    }

    private fun handleMap(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= 29) { "数据包过短" }
        val magic = ByteArray(4).also(buffer::get)
        require(magic.contentEquals(MAGIC) && buffer.get().toInt() == MAP_PACKET) { "不是地图数据包" }
        val width = buffer.int
        val height = buffer.int
        val resolution = buffer.float
        val originX = buffer.float
        val originY = buffer.float
        val rawSize = buffer.int
        val compressedSize = buffer.int
        require(width > 0 && height > 0 && rawSize == width * height && compressedSize in 0..buffer.remaining()) { "地图尺寸无效" }
        val compressed = ByteArray(compressedSize).also(buffer::get)
        val inflater = Inflater()
        val cells = try {
            inflater.setInput(compressed)
            ByteArray(rawSize).also { require(inflater.inflate(it) == rawSize && inflater.finished()) { "地图解压失败" } }
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("地图解压失败", error)
        } finally { inflater.end() }
        _state.value = _state.value.copy(map = OccupancyGrid(width, height, resolution, originX, originY, cells), feedback = "地图已更新：${width}×${height}")
    }

    private suspend fun command(name: String, pendingText: String): MappingResult {
        if (!_state.value.connected) return MappingResult(false, "请先连接 Jetson 建图服务")
        val id = "mapping-${ids.incrementAndGet()}"
        val response = CompletableDeferred<MappingResult>()
        pending[id] = response
        if (socket?.send(JSONObject().put("op", "command").put("id", id).put("command", name).toString()) != true) {
            pending.remove(id)
            return MappingResult(false, "发送命令失败")
        }
        return withTimeoutOrNull(15_000) { response.await() } ?: MappingResult(false, "$pendingText，但未收到 Jetson 响应")
    }

    private companion object {
        val MAGIC = byteArrayOf(73, 67, 65, 82)
        const val MAP_PACKET = 1
    }
}
