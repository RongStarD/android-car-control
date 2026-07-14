package cn.edu.xxq.carappv2.model

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected
}

enum class MappingAction {
    None,
    Starting,
    Saving,
    Stopping
}

enum class NavigationPlanner(val label: String, val command: String) {
    Dwa("DWA（n3）", "start_navigation_dwa"),
    Teb("TEB（n4）", "start_navigation_teb")
}

enum class NavigationAction {
    None,
    LoadingMap,
    StartingBase,
    StartingPlanner,
    PublishingInitialPose,
    PublishingGoal,
    Cancelling,
    Stopping,
    TakingOver
}

enum class OperatingMode(val wireValue: String, val label: String) {
    Idle("idle", "空闲"),
    Remote("remote", "独立遥控"),
    Mapping("mapping", "建图"),
    NavigationBase("navigation_base", "底盘/雷达基础"),
    Navigation("navigation", "导航"),
    ObstacleAvoidance("obstacle_avoidance", "雷达避障"),
    Following("following", "雷达跟随"),
    Guarding("guarding", "雷达警卫"),
    VisionTracking("vision_tracking", "视觉追踪"),
    Unknown("unknown", "未知");

    companion object {
        fun fromWire(value: String?): OperatingMode = entries.firstOrNull {
            it.wireValue == value?.trim()?.lowercase()
        } ?: Unknown
    }
}

data class JetsonEndpoint(
    val host: String = "10.39.132.165",
    val port: String = "9092"
) {
    fun webSocketUrl(): String = "ws://${host.trim()}:${port.trim()}"
}

data class CarUiState(
    val endpoint: JetsonEndpoint = JetsonEndpoint(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val currentMode: OperatingMode = OperatingMode.Unknown,
    val runtimePhase: String = "unknown",
    val ready: Boolean = false,
    val detail: String = "尚未连接小车",
    val error: String = "",
    val protocolVersion: Int? = null,
    val lastResponse: String = "-",
    val lastDriveCommand: String = "-",
    val sentFrameCount: Long = 0,
    val remoteControlArmed: Boolean = false,
    val mappingActive: Boolean = false,
    val mappingAction: MappingAction = MappingAction.None,
    val mappingLinearSpeed: Double = 0.20,
    val mappingAngularSpeed: Double = 1.00,
    val selectedNavigationPlanner: NavigationPlanner = NavigationPlanner.Dwa,
    val navigationAction: NavigationAction = NavigationAction.None,
    val navigationReady: Boolean = false,
    val navigationActive: Boolean = false,
    val navigationStatus: String = "未启动",
    val navigationGoalSequence: Long = 0L,
    val navigationGoalState: String = "none",
    val navigationResultStatus: Int? = null,
    val navigationGoalStateUncertain: Boolean = false,
    val navigationTakeoverPending: Boolean = false,
    val selectedInitialPose: RobotPose? = null,
    val selectedGoalPose: RobotPose? = null,
    val navigationWaypoints: List<RobotPose> = emptyList(),
    val navigationRouteActive: Boolean = false,
    val navigationRouteIndex: Int = 0,
    val slamMap: OccupancyGridMap? = null,
    val robotPose: RobotPose? = null,
    val laserScan: LaserScanFrame? = null,
    val localCostmap: OccupancyGridMap? = null,
    val globalCostmap: OccupancyGridMap? = null,
    val globalPath: NavigationPath? = null,
    val localPath: NavigationPath? = null,
    val localOverlayFrame: String = "",
    val localPlanFrame: String = ""
)
