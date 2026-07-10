package cn.edu.xxq.carcontrol.model

enum class ConnectionMode(val label: String) {
    Tcp("课程 TCP"),
    RosBridge("Jetson ROS")
}

enum class DriveDirection(val value: Int, val label: String) {
    Stop(0, "停止"),
    Forward(1, "前进"),
    Reverse(2, "后退"),
    StrafeLeft(3, "左移"),
    StrafeRight(4, "右移"),
    RotateLeft(5, "左旋"),
    RotateRight(6, "右旋"),
    Brake(7, "刹车")
}

data class Endpoint(
    val mode: ConnectionMode = ConnectionMode.RosBridge,
    val host: String = "10.39.132.165",
    val port: String = "8081",
    val videoPort: String = "6500"
)

data class TransportResult(
    val ok: Boolean,
    val message: String,
    val frame: String = ""
)

data class CarUiState(
    val endpoint: Endpoint = Endpoint(),
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val controlMode: ControlMode = ControlMode.Buttons,
    val lastCommand: String = "-",
    val lastFrame: String = "-",
    val feedback: String = "未连接",
    val trackingEnabled: Boolean = false,
    val recording: Boolean = false,
    val showVideo: Boolean = false,
    val wheelSpeeds: List<Int> = List(4) { 0 }
)

enum class ControlMode(val label: String) {
    Buttons("方向"),
    Joystick("摇杆"),
    Wheels("四轮")
}
