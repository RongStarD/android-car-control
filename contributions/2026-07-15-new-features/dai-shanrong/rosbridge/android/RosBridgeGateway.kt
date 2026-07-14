package cn.edu.xxq.carcontrol.data

import android.util.Base64
import cn.edu.xxq.carcontrol.model.DriveDirection
import cn.edu.xxq.carcontrol.model.Endpoint
import cn.edu.xxq.carcontrol.model.OccupancyGridMap
import cn.edu.xxq.carcontrol.model.RobotPose
import cn.edu.xxq.carcontrol.model.RosTelemetry
import cn.edu.xxq.carcontrol.model.TransportResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A ROSBridge v2 WebSocket client for the Android control and SLAM screens. */
class RosBridgeGateway : CarGateway {
    private val _telemetry = MutableStateFlow(RosTelemetry())
    val telemetry: StateFlow<RosTelemetry> = _telemetry.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()
    private val ids = AtomicLong(0)
    private val serviceCalls = ConcurrentHashMap<String, CompletableDeferred<TransportResult>>()
    private var socket: WebSocket? = null
    private var ready: CompletableDeferred<TransportResult>? = null
    private var mapToOdom: Transform2D? = null
    private var odomToBase: Transform2D? = null

    override suspend fun connect(endpoint: Endpoint): TransportResult {
        disconnect()
        val port = endpoint.port.toIntOrNull()
            ?: return TransportResult(false, "ROSBridge WebSocket 端口必须是数字")
        val connectionReady = CompletableDeferred<TransportResult>()
        ready = connectionReady
        _telemetry.value = _telemetry.value.copy(online = false, detail = "正在连接 ROSBridge…")
        val request = Request.Builder().url("ws://${endpoint.host.trim()}:$port").build()
        socket = client.newWebSocket(request, listener())
        return withTimeoutOrNull(5_000) { connectionReady.await() }
            ?: TransportResult(false, "ROSBridge 连接超时：${endpoint.host}:$port")
    }

    override suspend fun button(direction: DriveDirection): TransportResult = when (direction) {
        DriveDirection.Stop, DriveDirection.Brake -> publishTwist(command = "stop")
        DriveDirection.Forward -> publishTwist(linearX = LINEAR_SPEED, command = "forward")
        DriveDirection.Reverse -> publishTwist(linearX = -LINEAR_SPEED, command = "reverse")
        DriveDirection.StrafeLeft -> publishTwist(linearY = LINEAR_SPEED, command = "strafe_left")
        DriveDirection.StrafeRight -> publishTwist(linearY = -LINEAR_SPEED, command = "strafe_right")
        DriveDirection.RotateLeft -> publishTwist(angularZ = ANGULAR_SPEED, command = "rotate_left")
        DriveDirection.RotateRight -> publishTwist(angularZ = -ANGULAR_SPEED, command = "rotate_right")
    }

    override suspend fun joystick(x: Int, y: Int): TransportResult = joystick(x, y, LINEAR_SPEED, ANGULAR_SPEED)

    suspend fun joystick(x: Int, y: Int, linearSpeed: Double, angularSpeed: Double): TransportResult = publishTwist(
        linearX = y.coerceIn(-100, 100) / 100.0 * linearSpeed,
        angularZ = -x.coerceIn(-100, 100) / 100.0 * angularSpeed,
        command = "joystick"
    )

    suspend fun startMapping() = callTrigger("/app/start_mapping", "已请求启动 GMapping 建图")
    suspend fun stopMapping() = callTrigger("/app/stop_mapping", "已请求结束 GMapping 建图")
    suspend fun loadSavedMap() = callTrigger("/app/load_saved_map", "已请求读取已保存地图")
    suspend fun saveMap() = callTrigger("/app/save_map", "已请求保存地图")
    suspend fun startNavigationBase() = callTrigger("/app/start_navigation_base", "已请求启动导航基础节点")
    suspend fun startDwaNavigation() = callTrigger("/app/start_navigation_dwa", "已请求启动 DWA 导航")
    suspend fun startTebNavigation() = callTrigger("/app/start_navigation_teb", "已请求启动 TEB 导航")
    suspend fun cancelNavigation() = callTrigger("/app/cancel_navigation", "已请求取消导航")
    suspend fun stopNavigation() = callTrigger("/app/stop_navigation", "已请求退出导航环境")
    suspend fun emergencyStop() = callTrigger("/app/emergency_stop", "已请求紧急停止")

    override suspend fun disconnect() {
        socket?.close(1000, "App disconnected")
        socket = null
        ready?.cancel()
        ready = null
        serviceCalls.values.forEach { it.cancel() }
        serviceCalls.clear()
        _telemetry.value = _telemetry.value.copy(online = false, detail = "ROSBridge 已断开")
    }

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            advertiseCmdVel()
            subscribe("/map", throttleMs = 750)
            subscribe("/tf", throttleMs = 100)
            subscribe("/tf_static")
            subscribe("/odom", throttleMs = 100)
            subscribe("/odom_raw", throttleMs = 100)
            subscribe("/scan", throttleMs = 200)
            subscribe("/app/mapping_active")
            subscribe("/app/navigation_status", throttleMs = 100)
            subscribe("/app/navigation_ready")
            subscribe("/app/navigation_active")
            _telemetry.value = _telemetry.value.copy(online = true, detail = "ROSBridge 在线")
            ready?.complete(TransportResult(true, "已连接 ROSBridge WebSocket"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleMessage(JSONObject(text)) }
                .onFailure { _telemetry.value = _telemetry.value.copy(detail = "ROS 数据解析失败：${it.message}") }
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            val message = "ROSBridge 连接失败：${throwable.message ?: "未知错误"}"
            _telemetry.value = _telemetry.value.copy(online = false, detail = message)
            ready?.complete(TransportResult(false, message))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _telemetry.value = _telemetry.value.copy(online = false, detail = "ROSBridge 已关闭")
        }
    }

    private fun handleMessage(message: JSONObject) {
        when (message.optString("op")) {
            "publish" -> when (message.optString("topic")) {
                "/map" -> parseMap(message.getJSONObject("msg"))
                "/tf", "/tf_static" -> parseTransforms(message.getJSONObject("msg"))
                "/odom", "/odom_raw" -> parseOdometry(message.getJSONObject("msg"))
                "/scan" -> _telemetry.value = _telemetry.value.copy(
                    scanSamples = message.getJSONObject("msg").optJSONArray("ranges")?.length() ?: 0
                )
                "/app/mapping_active" -> _telemetry.value = _telemetry.value.copy(
                    mappingActive = message.getJSONObject("msg").optBoolean("data", false)
                )
                "/app/navigation_status" -> _telemetry.value = _telemetry.value.copy(
                    navigationStatus = message.getJSONObject("msg").optString("data", "未知导航状态")
                )
                "/app/navigation_ready" -> _telemetry.value = _telemetry.value.copy(
                    navigationReady = message.getJSONObject("msg").optBoolean("data", false)
                )
                "/app/navigation_active" -> _telemetry.value = _telemetry.value.copy(
                    navigationActive = message.getJSONObject("msg").optBoolean("data", false)
                )
            }
            "service_response" -> {
                val deferred = serviceCalls.remove(message.optString("id")) ?: return
                val values = message.optJSONObject("values")
                val success = message.optBoolean("result") && (values?.optBoolean("success", true) ?: true)
                val detail = values?.optString("message").orEmpty().ifBlank {
                    if (success) "ROS 服务执行成功" else "ROS 服务执行失败"
                }
                deferred.complete(TransportResult(success, detail))
                _telemetry.value = _telemetry.value.copy(mappingStatus = detail)
            }
        }
    }

    private fun advertiseCmdVel() {
        send(JSONObject().put("op", "advertise")
            .put("topic", "/cmd_vel")
            .put("type", "geometry_msgs/msg/Twist"))
        send(JSONObject().put("op", "advertise")
            .put("topic", "/app/goal_pose")
            .put("type", "geometry_msgs/msg/PoseStamped"))
        send(JSONObject().put("op", "advertise")
            .put("topic", "/initialpose")
            .put("type", "geometry_msgs/msg/PoseWithCovarianceStamped"))
    }

    private fun subscribe(topic: String, throttleMs: Int = 0) {
        send(JSONObject().put("op", "subscribe").put("topic", topic).apply {
            if (throttleMs > 0) put("throttle_rate", throttleMs)
        })
    }

    private fun publishTwist(
        linearX: Double = 0.0,
        linearY: Double = 0.0,
        angularZ: Double = 0.0,
        command: String
    ): TransportResult {
        if (!_telemetry.value.online) return TransportResult(false, "ROSBridge 尚未连接")
        val twist = JSONObject()
            .put("linear", JSONObject().put("x", linearX).put("y", linearY).put("z", 0.0))
            .put("angular", JSONObject().put("x", 0.0).put("y", 0.0).put("z", angularZ))
        val sent = send(JSONObject().put("op", "publish").put("topic", "/cmd_vel").put("msg", twist))
        return if (sent) TransportResult(true, command, twist.toString())
        else TransportResult(false, "发送 /cmd_vel 失败")
    }

    fun navigateTo(x: Float, y: Float, yaw: Float): TransportResult {
        if (!_telemetry.value.online) return TransportResult(false, "ROSBridge 尚未连接")
        val halfYaw = yaw / 2.0
        val pose = JSONObject()
            .put("header", JSONObject().put("frame_id", "map"))
            .put("pose", JSONObject()
                .put("position", JSONObject().put("x", x).put("y", y).put("z", 0.0))
                .put("orientation", JSONObject().put("x", 0.0).put("y", 0.0)
                    .put("z", sin(halfYaw)).put("w", cos(halfYaw))))
        return if (send(JSONObject().put("op", "publish").put("topic", "/app/goal_pose").put("msg", pose))) {
            TransportResult(true, "已发送导航目标：x=%.2f, y=%.2f".format(x, y), pose.toString())
        } else {
            TransportResult(false, "发送导航目标失败")
        }
    }

    fun setInitialPose(x: Float, y: Float, yaw: Float): TransportResult {
        if (!_telemetry.value.online) return TransportResult(false, "ROSBridge 尚未连接")
        val halfYaw = yaw / 2.0
        val covariance = JSONArray().apply {
            repeat(36) { put(0.0) }
            put(0, 0.25)
            put(7, 0.25)
            put(35, 0.068)
        }
        val message = JSONObject()
            .put("header", JSONObject().put("frame_id", "map"))
            .put("pose", JSONObject()
                .put("pose", JSONObject()
                    .put("position", JSONObject().put("x", x).put("y", y).put("z", 0.0))
                    .put("orientation", JSONObject().put("x", 0.0).put("y", 0.0)
                        .put("z", sin(halfYaw)).put("w", cos(halfYaw))))
                .put("covariance", covariance))
        return if (send(JSONObject().put("op", "publish").put("topic", "/initialpose").put("msg", message))) {
            TransportResult(true, "已设置初始位置：x=%.2f, y=%.2f".format(x, y), message.toString())
        } else {
            TransportResult(false, "设置初始位置失败")
        }
    }

    private suspend fun callTrigger(service: String, optimisticMessage: String): TransportResult {
        if (!_telemetry.value.online) return TransportResult(false, "ROSBridge 尚未连接")
        val id = "app-${ids.incrementAndGet()}"
        val response = CompletableDeferred<TransportResult>()
        serviceCalls[id] = response
        val sent = send(JSONObject().put("op", "call_service").put("id", id)
            .put("service", service).put("type", "std_srvs/srv/Trigger").put("args", JSONObject()))
        if (!sent) {
            serviceCalls.remove(id)
            return TransportResult(false, "调用 $service 失败")
        }
        return withTimeoutOrNull(10_000) { response.await() }
            ?: TransportResult(false, "$optimisticMessage，但未收到小车响应")
    }

    private fun parseMap(message: JSONObject) {
        val info = message.getJSONObject("info")
        val width = info.optInt("width")
        val height = info.optInt("height")
        if (width <= 0 || height <= 0) return
        val origin = info.optJSONObject("origin")?.optJSONObject("position")
        val cells = parseCells(message.opt("data"), width * height)
        _telemetry.value = _telemetry.value.copy(
            map = OccupancyGridMap(
                width = width,
                height = height,
                resolution = info.optDouble("resolution", 0.05).toFloat(),
                originX = origin?.optDouble("x", 0.0)?.toFloat() ?: 0f,
                originY = origin?.optDouble("y", 0.0)?.toFloat() ?: 0f,
                cells = cells
            ),
            detail = "地图已更新：${width}×${height}"
        )
    }

    private fun parseCells(value: Any?, size: Int): IntArray {
        val cells = IntArray(size) { -1 }
        when (value) {
            is JSONArray -> for (index in 0 until minOf(size, value.length())) cells[index] = value.optInt(index, -1)
            is String -> {
                val bytes = Base64.decode(value, Base64.DEFAULT)
                for (index in 0 until minOf(size, bytes.size)) cells[index] = bytes[index].toInt()
            }
        }
        return cells
    }

    private fun parseTransforms(message: JSONObject) {
        val transforms = message.optJSONArray("transforms") ?: return
        for (index in 0 until transforms.length()) {
            val transform = transforms.optJSONObject(index) ?: continue
            val parent = normalizeFrame(transform.optJSONObject("header")?.optString("frame_id").orEmpty())
            val child = normalizeFrame(transform.optString("child_frame_id"))
            val value = transform.optJSONObject("transform") ?: continue
            val translation = value.optJSONObject("translation") ?: continue
            val rotation = value.optJSONObject("rotation") ?: continue
            val pose = Transform2D(
                translation.optDouble("x").toFloat(),
                translation.optDouble("y").toFloat(),
                quaternionYaw(rotation)
            )
            when {
                parent == "map" && child in BASE_FRAMES -> setRobotPose(pose)
                parent == "map" && child == "odom" -> {
                    mapToOdom = pose
                    updateComposedPose()
                }
                parent == "odom" && child in BASE_FRAMES -> {
                    odomToBase = pose
                    updateComposedPose()
                }
            }
        }
    }

    private fun parseOdometry(message: JSONObject) {
        if (_telemetry.value.robotPose != null) return
        val position = message.optJSONObject("pose")?.optJSONObject("pose")?.optJSONObject("position") ?: return
        val rotation = message.optJSONObject("pose")?.optJSONObject("pose")?.optJSONObject("orientation") ?: return
        setRobotPose(Transform2D(position.optDouble("x").toFloat(), position.optDouble("y").toFloat(), quaternionYaw(rotation)))
    }

    private fun updateComposedPose() {
        val mapOdom = mapToOdom ?: return
        val odomBase = odomToBase ?: return
        val x = mapOdom.x + cos(mapOdom.yaw) * odomBase.x - sin(mapOdom.yaw) * odomBase.y
        val y = mapOdom.y + sin(mapOdom.yaw) * odomBase.x + cos(mapOdom.yaw) * odomBase.y
        setRobotPose(Transform2D(x.toFloat(), y.toFloat(), mapOdom.yaw + odomBase.yaw))
    }

    private fun setRobotPose(transform: Transform2D) {
        _telemetry.value = _telemetry.value.copy(robotPose = RobotPose(transform.x, transform.y, transform.yaw))
    }

    private fun quaternionYaw(rotation: JSONObject): Float {
        val x = rotation.optDouble("x")
        val y = rotation.optDouble("y")
        val z = rotation.optDouble("z")
        val w = rotation.optDouble("w", 1.0)
        return atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z)).toFloat()
    }

    private fun normalizeFrame(frame: String) = frame.removePrefix("/").lowercase()
    private fun send(message: JSONObject) = socket?.send(message.toString()) == true
    private data class Transform2D(val x: Float, val y: Float, val yaw: Float)

    private companion object {
        const val LINEAR_SPEED = 0.20
        const val ANGULAR_SPEED = 1.00
        val BASE_FRAMES = setOf("base_link", "base_footprint")
    }
}
