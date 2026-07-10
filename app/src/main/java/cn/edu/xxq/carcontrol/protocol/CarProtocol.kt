package cn.edu.xxq.carcontrol.protocol

import cn.edu.xxq.carcontrol.model.DriveDirection

/** Encoder for the reference project's `$01...#` TCP frames. */
object CarProtocol {
    fun button(direction: DriveDirection): String = frame("15", hex(direction.value))

    fun joystick(x: Int, y: Int): String = frame("10", signedByte(x) + signedByte(y))

    fun wheels(leftFront: Int, leftRear: Int, rightFront: Int, rightRear: Int): String =
        frame("21", listOf(leftFront, leftRear, rightFront, rightRear).joinToString("") { signedByte(it) })

    fun photo(): String = frame("60")
    fun startRecording(): String = frame("61")
    fun stopRecording(): String = frame("62")
    fun tracking(enabled: Boolean): String = frame(if (enabled) "63" else "64")

    private fun frame(command: String, payload: String = ""): String {
        val body = "01$command${hex(payload.length + 2)}$payload"
        return "\$$body${hex(checksum(body))}#"
    }

    private fun signedByte(value: Int): String {
        val safe = value.coerceIn(-100, 100)
        return hex(if (safe < 0) safe + 256 else safe)
    }

    private fun hex(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

    private fun checksum(body: String): Int = body.chunked(2).sumOf { it.toInt(16) } % 256
}
