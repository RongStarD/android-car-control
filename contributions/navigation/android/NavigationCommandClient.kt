package cn.edu.xxq.carcontrol.contribution.navigation

data class Pose2D(val x: Float, val y: Float, val yaw: Float = 0f)
data class NavigationResult(val ok: Boolean, val message: String)

interface NavigationTransport {
    suspend fun command(name: String, values: Map<String, Double> = emptyMap()): NavigationResult
}

enum class NavigationAlgorithm(val command: String) {
    DWA("start_navigation_dwa"),
    TEB("start_navigation_teb")
}

/** Navigation flow only; manual movement stays in the movement contribution. */
class NavigationCommandClient(private val transport: NavigationTransport) {
    suspend fun startBase() = transport.command("start_navigation_base")
    suspend fun start(algorithm: NavigationAlgorithm) = transport.command(algorithm.command)
    suspend fun cancel() = transport.command("cancel_navigation")
    suspend fun stop() = transport.command("stop_navigation")

    suspend fun setInitialPose(pose: Pose2D) = poseCommand("initial_pose", pose)
    suspend fun navigateTo(pose: Pose2D) = poseCommand("goal_pose", pose)

    private suspend fun poseCommand(command: String, pose: Pose2D) = transport.command(
        command,
        mapOf("x" to pose.x.toDouble(), "y" to pose.y.toDouble(), "yaw" to pose.yaw.toDouble())
    )
}
