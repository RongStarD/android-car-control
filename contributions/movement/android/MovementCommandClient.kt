package cn.edu.xxq.carcontrol.contribution.movement

/** Small contract so the movement feature stays independent of mapping and Nav2. */
interface MovementTransport {
    suspend fun command(name: String, values: Map<String, Double> = emptyMap()): MovementResult
}

data class MovementResult(val ok: Boolean, val message: String)

enum class MoveDirection { STOP, FORWARD, REVERSE, STRAFE_LEFT, STRAFE_RIGHT, ROTATE_LEFT, ROTATE_RIGHT }

/**
 * Maps UI intent to a `/cmd_vel`-compatible twist command.  A WebSocket adapter
 * can forward the resulting `twist` command to the Jetson movement bridge.
 */
class MovementCommandClient(private val transport: MovementTransport) {
    suspend fun move(direction: MoveDirection): MovementResult = when (direction) {
        MoveDirection.STOP -> twist(0.0, 0.0, 0.0)
        MoveDirection.FORWARD -> twist(DEFAULT_LINEAR_SPEED, 0.0, 0.0)
        MoveDirection.REVERSE -> twist(-DEFAULT_LINEAR_SPEED, 0.0, 0.0)
        MoveDirection.STRAFE_LEFT -> twist(0.0, DEFAULT_LINEAR_SPEED, 0.0)
        MoveDirection.STRAFE_RIGHT -> twist(0.0, -DEFAULT_LINEAR_SPEED, 0.0)
        MoveDirection.ROTATE_LEFT -> twist(0.0, 0.0, DEFAULT_ANGULAR_SPEED)
        MoveDirection.ROTATE_RIGHT -> twist(0.0, 0.0, -DEFAULT_ANGULAR_SPEED)
    }

    suspend fun joystick(
        x: Int,
        y: Int,
        linearSpeed: Double = DEFAULT_LINEAR_SPEED,
        angularSpeed: Double = DEFAULT_ANGULAR_SPEED
    ): MovementResult = twist(
        linearX = y.coerceIn(-100, 100) / 100.0 * linearSpeed,
        linearY = 0.0,
        angularZ = -x.coerceIn(-100, 100) / 100.0 * angularSpeed
    )

    suspend fun emergencyStop(): MovementResult = transport.command("emergency_stop")

    private suspend fun twist(linearX: Double, linearY: Double, angularZ: Double): MovementResult =
        transport.command(
            "twist",
            mapOf("linear_x" to linearX, "linear_y" to linearY, "angular_z" to angularZ)
        )

    private companion object {
        const val DEFAULT_LINEAR_SPEED = 0.20
        const val DEFAULT_ANGULAR_SPEED = 1.00
    }
}
