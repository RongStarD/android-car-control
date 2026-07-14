package cn.edu.xxq.carappv2.data

import cn.edu.xxq.carappv2.model.ConnectionStatus
import cn.edu.xxq.carappv2.model.JetsonEndpoint
import cn.edu.xxq.carappv2.model.LaserScanFrame
import cn.edu.xxq.carappv2.model.NavigationPath
import cn.edu.xxq.carappv2.model.OccupancyGridMap
import cn.edu.xxq.carappv2.model.OperatingMode
import cn.edu.xxq.carappv2.model.RobotPose
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class BridgeSnapshot(
    val connectionStatus: ConnectionStatus,
    val currentMode: OperatingMode? = null,
    val runtimePhase: String? = null,
    val ready: Boolean? = null,
    val detail: String? = null,
    val error: String? = null,
    val protocolVersion: Int? = null,
    val response: String? = null,
    val mappingActive: Boolean? = null,
    val navigationReady: Boolean? = null,
    val navigationActive: Boolean? = null,
    val navigationStatus: String? = null,
    val navigationGoalSequence: Long? = null,
    val navigationGoalState: String? = null,
    val navigationResultStatus: Int? = null,
    val navigationResultStatusPresent: Boolean = false,
    val localOverlayFrame: String? = null,
    val localPlanFrame: String? = null,
    val slamMap: OccupancyGridMap? = null,
    val robotPose: RobotPose? = null,
    val laserScan: LaserScanFrame? = null,
    val localCostmap: OccupancyGridMap? = null,
    val globalCostmap: OccupancyGridMap? = null,
    val globalPath: NavigationPath? = null,
    val localPath: NavigationPath? = null
)

class JetsonWebSocketClient(
    private val onSnapshot: (BridgeSnapshot) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var binaryRevision = 0L
    private val serviceCallbacks = ConcurrentHashMap<String, (Boolean, String) -> Unit>()

    fun connect(endpoint: JetsonEndpoint) {
        disconnect()
        val port = endpoint.port.toIntOrNull()
        if (endpoint.host.isBlank() || port == null || port !in 1..65535) {
            onSnapshot(
                BridgeSnapshot(
                    connectionStatus = ConnectionStatus.Disconnected,
                    error = "Jetson IP 或端口无效"
                )
            )
            return
        }

        onSnapshot(
            BridgeSnapshot(
                connectionStatus = ConnectionStatus.Connecting,
                detail = "正在连接 ${endpoint.webSocketUrl()}"
            )
        )
        val request = Request.Builder().url(endpoint.webSocketUrl()).build()
        socket = client.newWebSocket(request, listener())
    }

    fun disconnect() {
        sendTwist(0.0, 0.0, 0.0)
        socket?.close(1000, "Android V2 disconnected")
        socket = null
        serviceCallbacks.clear()
    }

    fun emergencyStop(): Boolean {
        val requestId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("id", requestId)
            .put("command", "emergency_stop")
            .toString()
        return socket?.send(payload) == true
    }

    fun requestService(command: String, onResponse: (Boolean, String) -> Unit = { _, _ -> }): Boolean {
        val requestId = UUID.randomUUID().toString()
        serviceCallbacks[requestId] = onResponse
        val sent = sendCommand(
            JSONObject()
                .put("id", requestId)
                .put("command", command)
        )
        if (!sent) {
            serviceCallbacks.remove(requestId)
            onResponse(false, "WebSocket 尚未连接")
        }
        return sent
    }

    fun requestPose(
        command: String,
        x: Float,
        y: Float,
        yaw: Float,
        onResponse: (Boolean, String) -> Unit = { _, _ -> }
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        serviceCallbacks[requestId] = onResponse
        val sent = sendCommand(
            JSONObject()
                .put("id", requestId)
                .put("command", command)
                .put("x", x)
                .put("y", y)
                .put("yaw", yaw)
        )
        if (!sent) {
            serviceCallbacks.remove(requestId)
            onResponse(false, "WebSocket 尚未连接")
        }
        return sent
    }

    fun sendTwist(linearX: Double, linearY: Double, angularZ: Double): Boolean = sendCommand(
        JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("command", "twist")
            .put("linear_x", linearX)
            .put("linear_y", linearY)
            .put("angular_z", angularZ)
    )

    private fun sendCommand(payload: JSONObject): Boolean = socket?.send(payload.toString()) == true

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            onSnapshot(
                BridgeSnapshot(
                    connectionStatus = ConnectionStatus.Connected,
                    detail = "WebSocket 已连接"
                )
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            runCatching { JSONObject(text) }
                .onSuccess(::handleMessage)
                .onFailure {
                    onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            error = "状态消息解析失败：${it.message}"
                        )
                    )
                }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (socket !== webSocket) return
            runCatching {
                AppBridgeBinaryDecoder.decode(
                    payload = bytes.toByteArray(),
                    revision = ++binaryRevision
                )
            }.onSuccess { message ->
                when (message) {
                    is BridgeBinaryMessage.MapGrid -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            slamMap = message.map
                        )
                    )

                    is BridgeBinaryMessage.Pose -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            robotPose = message.pose
                        )
                    )

                    is BridgeBinaryMessage.Scan -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            laserScan = message.scan
                        )
                    )

                    is BridgeBinaryMessage.LocalCostmap -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            localCostmap = message.map
                        )
                    )

                    is BridgeBinaryMessage.GlobalCostmap -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            globalCostmap = message.map
                        )
                    )

                    is BridgeBinaryMessage.GlobalPath -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            globalPath = message.path
                        )
                    )

                    is BridgeBinaryMessage.LocalPath -> onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            localPath = message.path
                        )
                    )

                    is BridgeBinaryMessage.Unsupported -> Unit
                }
            }.onFailure {
                onSnapshot(
                    BridgeSnapshot(
                        connectionStatus = ConnectionStatus.Connected,
                        error = "建图数据解析失败：${it.message}"
                    )
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            serviceCallbacks.clear()
            onSnapshot(
                BridgeSnapshot(
                    connectionStatus = ConnectionStatus.Disconnected,
                    detail = "连接已断开",
                    error = throwable.message ?: "未知网络错误"
                )
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socket = null
            serviceCallbacks.clear()
            onSnapshot(
                BridgeSnapshot(
                    connectionStatus = ConnectionStatus.Disconnected,
                    currentMode = OperatingMode.Unknown,
                    ready = false,
                    detail = "连接已关闭"
                )
            )
        }
    }

    private fun handleMessage(message: JSONObject) {
        when (message.optString("op")) {
            "hello" -> onSnapshot(
                BridgeSnapshot(
                    connectionStatus = ConnectionStatus.Connected,
                    protocolVersion = message.optInt("protocol"),
                    detail = "小车桥接协议已就绪"
                )
            )

            "runtime" -> onSnapshot(runtimeSnapshot(message))
            "state" -> {
                if (
                    message.has("mapping_active") ||
                    message.has("navigation_ready") ||
                    message.has("navigation_active") ||
                    message.has("navigation_status") ||
                    message.has("navigation_goal") ||
                    message.has("navigation_goal_sequence") ||
                    message.has("navigation_goal_state") ||
                    message.has("navigation_result_status") ||
                    message.has("local_overlay_frame") ||
                    message.has("local_plan_frame")
                ) {
                    val navigationGoal = message.optJSONObject("navigation_goal")
                    onSnapshot(
                        BridgeSnapshot(
                            connectionStatus = ConnectionStatus.Connected,
                            mappingActive = if (message.has("mapping_active")) {
                                message.optBoolean("mapping_active", false)
                            } else {
                                null
                            },
                            navigationReady = if (message.has("navigation_ready")) {
                                message.optBoolean("navigation_ready", false)
                            } else {
                                null
                            },
                            navigationActive = if (message.has("navigation_active")) {
                                message.optBoolean("navigation_active", false)
                            } else {
                                null
                            },
                            navigationStatus = if (message.has("navigation_status")) {
                                message.optString("navigation_status", "未启动")
                            } else {
                                null
                            },
                            navigationGoalSequence = if (
                                navigationGoal?.has("sequence") == true &&
                                !navigationGoal.isNull("sequence")
                            ) {
                                navigationGoal.optLong("sequence", 0L)
                            } else if (
                                message.has("navigation_goal_sequence") &&
                                !message.isNull("navigation_goal_sequence")
                            ) {
                                message.optLong("navigation_goal_sequence", 0L)
                            } else {
                                null
                            },
                            navigationGoalState = if (
                                navigationGoal?.has("state") == true &&
                                !navigationGoal.isNull("state")
                            ) {
                                normalizeNavigationGoalState(navigationGoal.optString("state", "none"))
                            } else if (
                                message.has("navigation_goal_state") &&
                                !message.isNull("navigation_goal_state")
                            ) {
                                normalizeNavigationGoalState(
                                    message.optString("navigation_goal_state", "none")
                                )
                            } else {
                                null
                            },
                            navigationResultStatus = if (
                                navigationGoal?.has("result_status") == true &&
                                !navigationGoal.isNull("result_status")
                            ) {
                                navigationGoal.optInt("result_status")
                            } else if (
                                message.has("navigation_result_status") &&
                                !message.isNull("navigation_result_status")
                            ) {
                                message.optInt("navigation_result_status")
                            } else {
                                null
                            },
                            navigationResultStatusPresent =
                                navigationGoal?.has("result_status") == true ||
                                    message.has("navigation_result_status"),
                            localOverlayFrame = if (
                                message.has("local_overlay_frame") &&
                                !message.isNull("local_overlay_frame")
                            ) {
                                message.optString("local_overlay_frame").trim()
                            } else {
                                null
                            },
                            localPlanFrame = if (
                                message.has("local_plan_frame") &&
                                !message.isNull("local_plan_frame")
                            ) {
                                message.optString("local_plan_frame").trim()
                            } else {
                                null
                            }
                        )
                    )
                }
                val runtime = message.optJSONObject("runtime")
                if (runtime != null) onSnapshot(runtimeSnapshot(runtime))
            }

            "response" -> onSnapshot(
                BridgeSnapshot(
                    connectionStatus = ConnectionStatus.Connected,
                    response = message.optString("message", "小车已响应"),
                    error = if (message.optBoolean("ok", false)) "" else message.optString("message")
                ).also {
                    val requestId = message.optString("id")
                    serviceCallbacks.remove(requestId)?.invoke(
                        message.optBoolean("ok", false),
                        message.optString("message", "小车已响应")
                    )
                }
            )
        }
    }

    private fun runtimeSnapshot(message: JSONObject) = BridgeSnapshot(
        connectionStatus = ConnectionStatus.Connected,
        currentMode = OperatingMode.fromWire(message.optString("mode")),
        runtimePhase = message.optString("phase", "unknown"),
        ready = message.optBoolean("ready", false),
        detail = message.optString("detail", "已收到小车运行状态"),
        error = message.optString("error", "")
    )

}

internal fun normalizeNavigationGoalState(value: String): String = when (
    val normalized = value.trim().lowercase()
) {
    "idle" -> "none"
    "pending_accept" -> "pending"
    "cancel_requested" -> "cancel_pending"
    "stopped" -> "canceled"
    "failed", "finished" -> "error"
    else -> normalized
}
