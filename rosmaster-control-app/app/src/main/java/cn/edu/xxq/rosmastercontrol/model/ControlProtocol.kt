package cn.edu.xxq.rosmastercontrol.model

import org.json.JSONObject

const val CONTROL_PROTOCOL_VERSION = 1

enum class DriveCommand(val code: Int, val label: String) {
    FORWARD(1, "前进"),
    BACKWARD(2, "后退"),
    STRAFE_LEFT(3, "左平移"),
    STRAFE_RIGHT(4, "右平移"),
    TURN_LEFT(5, "左转"),
    TURN_RIGHT(6, "右转");

    companion object {
        fun fromCode(code: Int): DriveCommand? = entries.firstOrNull { it.code == code }
    }
}

data class RobotState(
    val serialReady: Boolean = false,
    val cameraReady: Boolean = false,
    val moving: Boolean = false,
    val command: DriveCommand? = null,
    val speed: Int = 0,
    val durationMs: Int = 0,
    val followLine: Boolean = false,
    val message: String = "等待小车状态",
)

sealed interface ServerMessage {
    data class Hello(
        val protocol: Int,
        val message: String,
        val state: RobotState?,
    ) : ServerMessage

    data class State(val value: RobotState) : ServerMessage

    data class Response(
        val id: Long,
        val ok: Boolean,
        val message: String,
    ) : ServerMessage

    data class Unknown(val op: String) : ServerMessage
}

object ControlProtocol {
    fun drive(id: Long, command: DriveCommand, speed: Int, durationMs: Int): String {
        require(speed in ControlRules.speeds) { "不支持的速度档：$speed" }
        require(durationMs in ControlRules.durationsMs) { "不支持的持续时间：$durationMs" }
        return JSONObject()
            .put("op", "drive")
            .put("id", id)
            .put("command", command.code)
            .put("speed", speed)
            .put("duration_ms", durationMs)
            .toString()
    }

    fun heartbeat(id: Long): String = JSONObject()
        .put("op", "heartbeat")
        .put("id", id)
        .toString()

    fun stop(id: Long): String = JSONObject()
        .put("op", "stop")
        .put("id", id)
        .toString()

    fun followLine(id: Long, enabled: Boolean): String = JSONObject()
        .put("op", "follow_line")
        .put("id", id)
        .put("enabled", enabled)
        .toString()

    fun emergencyStop(id: Long): String = JSONObject()
        .put("op", "emergency_stop")
        .put("id", id)
        .toString()

    fun parse(text: String): ServerMessage {
        val json = JSONObject(text)
        return when (val op = json.optString("op")) {
            "hello" -> ServerMessage.Hello(
                protocol = json.optInt("protocol", 0),
                message = json.optString("message", "控制桥已连接"),
                state = json.optJSONObject("state")?.let(::parseState),
            )

            "state" -> ServerMessage.State(parseState(json))

            "response" -> ServerMessage.Response(
                id = json.optLong("id", -1L),
                ok = json.optBoolean("ok", false),
                message = json.optString("message", if (json.optBoolean("ok")) "操作成功" else "操作失败"),
            )

            else -> ServerMessage.Unknown(op)
        }
    }

    private fun parseState(json: JSONObject): RobotState = RobotState(
        serialReady = json.optBoolean("serial_ready", false),
        cameraReady = json.optBoolean("camera_ready", false),
        moving = json.optBoolean("moving", false),
        command = DriveCommand.fromCode(json.optInt("command", 0)),
        speed = json.optInt("speed", 0),
        durationMs = json.optInt("duration_ms", 0),
        followLine = json.optBoolean("follow_line", false),
        message = json.optString("message", "状态已更新"),
    )
}

object ControlRules {
    val speeds = listOf(30, 50, 70, 100)
    val durationsMs = listOf(0, 500, 1_000)

    fun durationLabel(durationMs: Int): String = when (durationMs) {
        0 -> "持续"
        500 -> "0.5s"
        1_000 -> "1s"
        else -> error("不支持的持续时间：$durationMs")
    }

    fun isValidHost(host: String): Boolean {
        val value = host.trim()
        return value.isNotEmpty() &&
            value.length <= 253 &&
            value.none { it.isWhitespace() || it == '/' || it == ':' }
    }

    fun parsePort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..65535 }
}
