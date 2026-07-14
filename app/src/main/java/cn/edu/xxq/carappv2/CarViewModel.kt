package cn.edu.xxq.carappv2

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.xxq.carappv2.data.BridgeSnapshot
import cn.edu.xxq.carappv2.data.JetsonWebSocketClient
import cn.edu.xxq.carappv2.model.CarUiState
import cn.edu.xxq.carappv2.model.ConnectionStatus
import cn.edu.xxq.carappv2.model.JetsonEndpoint
import cn.edu.xxq.carappv2.model.MappingAction
import cn.edu.xxq.carappv2.model.NavigationAction
import cn.edu.xxq.carappv2.model.NavigationPlanner
import cn.edu.xxq.carappv2.model.NavigationSafetyRules
import cn.edu.xxq.carappv2.model.OperatingMode
import cn.edu.xxq.carappv2.model.RobotPose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CarViewModel(application: Application) : AndroidViewModel(application) {
    private data class ActiveDrive(
        val linearX: Double = 0.0,
        val linearY: Double = 0.0,
        val angularZ: Double = 0.0,
        val command: String = "停止"
    )

    private val preferences = application.getSharedPreferences("car_app_v2", 0)
    private val _state = MutableStateFlow(
        CarUiState(
            endpoint = JetsonEndpoint(
                host = preferences.getString("host", null) ?: "10.39.132.165",
                port = preferences.getString("port", null) ?: "9092"
            )
        )
    )
    val state: StateFlow<CarUiState> = _state.asStateFlow()

    private val client = JetsonWebSocketClient(::applySnapshot)
    private var activeDrive = ActiveDrive()
    private var drivePublishJob: Job? = null
    private var mappingActionTimeoutJob: Job? = null
    private var navigationActionTimeoutJob: Job? = null
    private var takeoverZeroGuardJob: Job? = null
    private var navigationRouteJob: Job? = null
    private var navigationActionToken = 0L
    private var pendingGoalBaselineSequence: Long? = null
    private var routeGoalSequence: Long? = null
    private var handledRouteTerminalSequence: Long? = null
    private var cancelTargetSequence: Long? = null
    private var cancelTargetBaselineSequence: Long? = null
    private val runningGoalStates = setOf("pending", "active", "cancel_pending")
    private val terminalGoalStates = setOf(
        "succeeded",
        "canceled",
        "aborted",
        "rejected",
        "error"
    )
    private val knownGoalStates = runningGoalStates + terminalGoalStates + "none"

    fun updateHost(host: String) {
        _state.update {
            it.copy(
                endpoint = it.endpoint.copy(host = host),
                error = "",
                detail = "连接地址已修改"
            )
        }
    }

    fun updatePort(port: String) {
        _state.update {
            it.copy(
                endpoint = it.endpoint.copy(port = port),
                error = "",
                detail = "连接端口已修改"
            )
        }
    }

    fun connect() {
        val endpoint = _state.value.endpoint
        preferences.edit().putString("host", endpoint.host).putString("port", endpoint.port).apply()
        client.connect(endpoint)
    }

    fun disconnect() {
        safeStop()
        stopNavigationRoute()
        mappingActionTimeoutJob?.cancel()
        mappingActionTimeoutJob = null
        navigationActionTimeoutJob?.cancel()
        navigationActionTimeoutJob = null
        navigationActionToken += 1
        pendingGoalBaselineSequence = null
        routeGoalSequence = null
        handledRouteTerminalSequence = null
        cancelTargetSequence = null
        cancelTargetBaselineSequence = null
        client.disconnect()
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.Disconnected,
                remoteControlArmed = false,
                mappingAction = MappingAction.None,
                navigationAction = NavigationAction.None,
                navigationTakeoverPending = false,
                navigationReady = false,
                navigationActive = false,
                navigationGoalSequence = 0L,
                navigationGoalState = "none",
                navigationResultStatus = null,
                navigationGoalStateUncertain = false,
                detail = "已主动断开",
                error = ""
            )
        }
    }

    fun emergencyStop() {
        drivePublishJob?.cancel()
        drivePublishJob = null
        stopTakeoverZeroGuard()
        clearNavigationActionTimeout()
        stopNavigationRoute()
        navigationActionToken += 1
        pendingGoalBaselineSequence = null
        cancelTargetSequence = null
        cancelTargetBaselineSequence = null
        activeDrive = ActiveDrive()
        client.sendTwist(0.0, 0.0, 0.0)
        val sent = client.emergencyStop()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationTakeoverPending = false,
                navigationAction = NavigationAction.None,
                navigationGoalStateUncertain = false,
                detail = if (sent) "紧急停止请求已发送" else "紧急停止发送失败",
                error = if (sent) "" else "WebSocket 尚未连接"
            )
        }
    }

    fun armRemoteControl() {
        val current = _state.value
        if (current.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        if (current.currentMode == OperatingMode.Mapping) {
            _state.update { it.copy(error = "建图运行中，请在 SLAM 建图页启用 m3 控制") }
            return
        }
        if (current.currentMode == OperatingMode.Navigation) {
            _state.update { it.copy(error = "导航运行中，不能直接启用独立遥控") }
            return
        }
        client.sendTwist(0.0, 0.0, 0.0)
        _state.update {
            it.copy(
                remoteControlArmed = true,
                detail = "独立遥控已启用",
                error = ""
            )
        }
    }

    fun disarmRemoteControl() {
        safeStop()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                detail = "独立遥控已停用，小车已停车"
            )
        }
    }

    fun startMapping() {
        if (_state.value.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        safeStop()
        stopNavigationRoute()
        navigationActionToken += 1
        pendingGoalBaselineSequence = null
        cancelTargetSequence = null
        cancelTargetBaselineSequence = null
        _state.update {
            it.copy(
                remoteControlArmed = false,
                mappingAction = MappingAction.Starting,
                navigationAction = NavigationAction.None,
                navigationReady = false,
                navigationActive = false,
                navigationGoalSequence = 0L,
                navigationGoalState = "none",
                navigationResultStatus = null,
                navigationGoalStateUncertain = false,
                navigationTakeoverPending = false,
                slamMap = null,
                robotPose = null,
                laserScan = null,
                localCostmap = null,
                globalCostmap = null,
                globalPath = null,
                localPath = null,
                detail = "正在请求启动 m1…",
                error = ""
            )
        }
        scheduleMappingActionTimeout(MappingAction.Starting, "m1 启动响应超时，请检查桥接服务")
        client.requestService("start_mapping") { ok, message ->
            clearMappingActionTimeout()
            _state.update {
                it.copy(
                    mappingAction = MappingAction.None,
                    detail = if (ok) "m1 已受理，等待 GMapping、雷达、TF 与地图" else "m1 启动失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun armMappingControl() {
        val current = _state.value
        val canDrive = current.connectionStatus == ConnectionStatus.Connected &&
            current.currentMode == OperatingMode.Mapping &&
            current.ready &&
            current.slamMap != null &&
            current.mappingAction == MappingAction.None
        if (!canDrive) {
            _state.update { it.copy(error = "等待 m1 建图就绪并收到地图后才能启用 m3") }
            return
        }
        client.sendTwist(0.0, 0.0, 0.0)
        _state.update {
            it.copy(
                remoteControlArmed = true,
                detail = "建图 m3 控制已启用",
                error = ""
            )
        }
    }

    fun updateMappingLinearSpeed(value: Double) {
        if (drivePublishJob?.isActive == true) safeStop()
        _state.update {
            it.copy(
                mappingLinearSpeed = value.coerceIn(0.05, 0.50),
                detail = "建图线速度已调整",
                error = ""
            )
        }
    }

    fun updateMappingAngularSpeed(value: Double) {
        if (drivePublishJob?.isActive == true) safeStop()
        _state.update {
            it.copy(
                mappingAngularSpeed = value.coerceIn(0.20, 2.00),
                detail = "建图角速度已调整",
                error = ""
            )
        }
    }

    fun saveMap() {
        val current = _state.value
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Mapping ||
            !current.ready
        ) {
            _state.update { it.copy(error = "GMapping 就绪后才能保存地图") }
            return
        }
        safeStop()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                mappingAction = MappingAction.Saving,
                detail = "正在启动 m4 保存地图…",
                error = ""
            )
        }
        scheduleMappingActionTimeout(MappingAction.Saving, "m4 保存响应超时，请检查保存进程")
        client.requestService("save_map") { ok, message ->
            clearMappingActionTimeout()
            _state.update {
                it.copy(
                    mappingAction = MappingAction.None,
                    detail = if (ok) "m4 保存命令已启动" else "m4 启动失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun stopMapping() {
        if (_state.value.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        safeStop()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                mappingAction = MappingAction.Stopping,
                detail = "正在结束 m1…",
                error = ""
            )
        }
        scheduleMappingActionTimeout(MappingAction.Stopping, "结束建图响应超时，请检查 m1 进程")
        client.requestService("stop_mapping") { ok, message ->
            clearMappingActionTimeout()
            _state.update {
                it.copy(
                    mappingAction = MappingAction.None,
                    mappingActive = if (ok) false else it.mappingActive,
                    detail = if (ok) "建图已结束，小车已停车" else "结束建图失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun selectNavigationPlanner(planner: NavigationPlanner) {
        if (
            _state.value.navigationActive ||
            _state.value.navigationRouteActive ||
            _state.value.navigationGoalStateUncertain ||
            pendingGoalBaselineSequence != null ||
            isGoalRunning(_state.value.navigationGoalState)
        ) {
            _state.update { it.copy(error = "请先取消当前目标，再切换导航算法") }
            return
        }
        _state.update {
            it.copy(
                selectedNavigationPlanner = planner,
                detail = "已选择 ${planner.label}",
                error = ""
            )
        }
    }

    fun loadSavedMap() {
        val current = _state.value
        if (current.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        if (current.currentMode == OperatingMode.Mapping) {
            _state.update { it.copy(error = "建图正在运行，请先保存并结束 m1") }
            return
        }
        if (current.currentMode == OperatingMode.Navigation) {
            _state.update { it.copy(error = "Nav2 已启动，将由 Nav2 地图服务器提供地图") }
            return
        }
        safeStop()
        stopNavigationRoute()
        pendingGoalBaselineSequence = null
        cancelTargetSequence = null
        cancelTargetBaselineSequence = null
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationAction = NavigationAction.LoadingMap,
                navigationGoalStateUncertain = false,
                selectedInitialPose = null,
                selectedGoalPose = null,
                navigationWaypoints = emptyList(),
                navigationRouteIndex = 0,
                slamMap = null,
                localCostmap = null,
                globalCostmap = null,
                globalPath = null,
                localPath = null,
                detail = "正在读取 yahboomcar.yaml…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.LoadingMap,
            actionToken,
            "读取已保存地图响应超时"
        )
        client.requestService("load_saved_map") { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.LoadingMap, actionToken)) {
                return@requestService
            }
            clearNavigationActionTimeout()
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    detail = if (ok) "已请求显示保存地图，等待 /map" else "读取保存地图失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun startNavigationBase() {
        val current = _state.value
        if (current.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        if (current.currentMode == OperatingMode.Mapping) {
            _state.update { it.copy(error = "请先保存并结束 SLAM 建图，再启动 n1") }
            return
        }
        safeStop()
        stopNavigationRoute()
        clearNavigationTelemetry()
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationAction = NavigationAction.StartingBase,
                navigationTakeoverPending = false,
                detail = "正在请求启动 n1…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.StartingBase,
            actionToken,
            "n1 启动响应超时"
        )
        client.requestService("start_navigation_base") { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.StartingBase, actionToken)) {
                return@requestService
            }
            clearNavigationActionTimeout()
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    detail = if (ok) "n1 已受理，等待底盘、雷达与 TF" else "n1 启动失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun startSelectedNavigation() {
        val current = _state.value
        val baseReady = current.currentMode == OperatingMode.NavigationBase && current.ready
        val switchingPlanner = current.currentMode == OperatingMode.Navigation && !current.navigationActive
        if (current.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        if (!baseReady && !switchingPlanner) {
            _state.update { it.copy(error = "请先启动 n1 并等待 navigation_base / ready") }
            return
        }
        if (
            current.navigationActive ||
            current.navigationRouteActive ||
            current.navigationGoalStateUncertain ||
            pendingGoalBaselineSequence != null ||
            isGoalRunning(current.navigationGoalState)
        ) {
            _state.update { it.copy(error = "请先取消当前导航目标") }
            return
        }
        safeStop()
        stopNavigationRoute()
        clearNavigationTelemetry(keepMap = true)
        val planner = current.selectedNavigationPlanner
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationAction = NavigationAction.StartingPlanner,
                navigationReady = false,
                navigationTakeoverPending = false,
                detail = "正在请求启动 ${planner.label}…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.StartingPlanner,
            actionToken,
            "${planner.label} 启动响应超时"
        )
        client.requestService(planner.command) { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.StartingPlanner, actionToken)) {
                return@requestService
            }
            clearNavigationActionTimeout()
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    detail = if (ok) {
                        "${planner.label} 已受理，等待 Nav2 与初始位姿"
                    } else {
                        "${planner.label} 启动失败"
                    },
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun selectInitialPose(x: Float, y: Float) {
        val current = _state.value
        if (NavigationSafetyRules.hasInitialPoseConflict(current)) {
            _state.update { it.copy(error = "导航目标、路线或人工接管期间禁止修改初始位姿") }
            return
        }
        val yaw = current.selectedInitialPose?.yaw ?: current.robotPose?.yaw ?: 0f
        val pose = RobotPose(x, y, yaw)
        val validationError = navigationPoseValidationError(current, pose, "初始位姿")
        if (validationError != null) {
            _state.update { it.copy(selectedInitialPose = null, error = validationError) }
            return
        }
        _state.update {
            it.copy(
                selectedInitialPose = pose,
                detail = "已选择初始位姿，请核对朝向后发布",
                error = ""
            )
        }
    }

    fun updateInitialPoseYaw(yaw: Float) {
        _state.update { current ->
            current.copy(
                selectedInitialPose = current.selectedInitialPose?.copy(yaw = normalizeYaw(yaw)),
                error = ""
            )
        }
    }

    fun selectNavigationGoal(x: Float, y: Float) {
        val current = _state.value
        val yaw = current.selectedGoalPose?.yaw ?: 0f
        val pose = RobotPose(x, y, yaw)
        val validationError = navigationPoseValidationError(current, pose, "目标点")
        if (validationError != null) {
            _state.update { it.copy(selectedGoalPose = null, error = validationError) }
            return
        }
        _state.update {
            it.copy(
                selectedGoalPose = pose,
                detail = "已选择目标点，请核对朝向",
                error = ""
            )
        }
    }

    fun updateNavigationGoalYaw(yaw: Float) {
        _state.update { current ->
            current.copy(
                selectedGoalPose = current.selectedGoalPose?.copy(yaw = normalizeYaw(yaw)),
                error = ""
            )
        }
    }

    fun addNavigationWaypoint() {
        val current = _state.value
        val goal = current.selectedGoalPose
        if (goal == null) {
            _state.update { it.copy(error = "请先在地图上选择目标点") }
            return
        }
        val validationError = navigationPoseValidationError(current, goal, "目标点")
        if (validationError != null) {
            _state.update { it.copy(selectedGoalPose = null, error = validationError) }
            return
        }
        if (current.navigationWaypoints.size >= 20) {
            _state.update { it.copy(error = "本版本每条路线最多 20 个航点") }
            return
        }
        _state.update {
            it.copy(
                navigationWaypoints = it.navigationWaypoints + goal,
                detail = "已加入第 ${it.navigationWaypoints.size + 1} 个航点",
                error = ""
            )
        }
    }

    fun clearNavigationWaypoints() {
        if (_state.value.navigationRouteActive) {
            _state.update { it.copy(error = "路线执行中，请先取消导航") }
            return
        }
        navigationRouteJob?.cancel()
        navigationRouteJob = null
        _state.update {
            it.copy(
                navigationWaypoints = emptyList(),
                navigationRouteIndex = 0,
                detail = "航点列表已清空",
                error = ""
            )
        }
    }

    fun startWaypointRoute() {
        val current = _state.value
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Navigation ||
            !(current.ready && current.navigationReady)
        ) {
            _state.update { it.copy(error = "请等待 navigation / ready") }
            return
        }
        if (current.navigationWaypoints.isEmpty()) {
            _state.update { it.copy(error = "请至少添加一个航点") }
            return
        }
        val validated = validatedNavigationSelections(current)
        if (validated == null) {
            _state.update { it.copy(error = "尚未收到可用于复验航点的静态地图") }
            return
        }
        if (validated.removedWaypointCount > 0) {
            _state.update {
                it.copy(
                    navigationWaypoints = validated.waypoints,
                    error = "${validated.removedWaypointCount} 个航点已不在自由区域，已清除；请检查路线后重试"
                )
            }
            return
        }
        if (
            NavigationSafetyRules.hasGoalDispatchConflict(
                current,
                hasUnconfirmedGoalRequest = pendingGoalBaselineSequence != null
            )
        ) {
            _state.update { it.copy(error = "已有导航任务正在执行") }
            return
        }
        safeStop()
        pendingGoalBaselineSequence = null
        routeGoalSequence = null
        handledRouteTerminalSequence = null
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationTakeoverPending = false,
                navigationRouteActive = true,
                navigationRouteIndex = 0,
                detail = "开始多航点路线，共 ${it.navigationWaypoints.size} 个航点",
                error = ""
            )
        }
        sendRouteWaypoint(0)
    }

    fun publishInitialPose() {
        val current = _state.value
        val pose = current.selectedInitialPose
        val phaseAllowsPose = current.runtimePhase in setOf(
            "awaiting_initial_pose",
            "waiting_local_costmap",
            "waiting_global_costmap",
            "waiting_action",
            "ready"
        )
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Navigation ||
            !phaseAllowsPose ||
            pose == null
        ) {
            _state.update { it.copy(error = "请等待 Nav2 提示设置初始位姿，并先在地图上选点") }
            return
        }
        if (NavigationSafetyRules.hasInitialPoseConflict(current)) {
            _state.update {
                it.copy(error = "导航目标、路线或人工接管期间禁止重新发布初始位姿")
            }
            return
        }
        val validationError = navigationPoseValidationError(current, pose, "初始位姿")
        if (validationError != null) {
            _state.update { it.copy(selectedInitialPose = null, error = validationError) }
            return
        }
        safeStop()
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationAction = NavigationAction.PublishingInitialPose,
                detail = "正在发布 /initialpose…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.PublishingInitialPose,
            actionToken,
            "初始位姿发布响应超时"
        )
        client.requestPose("initial_pose", pose.x, pose.y, pose.yaw) { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.PublishingInitialPose, actionToken)) {
                return@requestPose
            }
            clearNavigationActionTimeout()
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    detail = if (ok) "初始位姿已发布，等待代价地图与 Action Server" else "初始位姿发布失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun publishNavigationGoal() {
        val current = _state.value
        val goal = current.selectedGoalPose
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Navigation ||
            !(current.ready && current.navigationReady) ||
            goal == null ||
            NavigationSafetyRules.hasGoalDispatchConflict(
                current,
                hasUnconfirmedGoalRequest = pendingGoalBaselineSequence != null
            )
        ) {
            _state.update { it.copy(error = "请等待 navigation / ready，且当前没有导航目标后再发送") }
            return
        }
        // navigationActive is deliberately checked by hasGoalDispatchConflict even if the
        // structured goal state has not arrived, preventing a duplicate goal during state skew.
        val validationError = navigationPoseValidationError(current, goal, "目标点")
        if (validationError != null) {
            _state.update { it.copy(selectedGoalPose = null, error = validationError) }
            return
        }
        safeStop()
        pendingGoalBaselineSequence = current.navigationGoalSequence
        routeGoalSequence = null
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationTakeoverPending = false,
                navigationAction = NavigationAction.PublishingGoal,
                detail = "正在发送导航目标…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.PublishingGoal,
            actionToken,
            "导航目标状态确认超时"
        )
        client.requestPose("goal_pose", goal.x, goal.y, goal.yaw) { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.PublishingGoal, actionToken)) {
                return@requestPose
            }
            if (!ok) {
                clearNavigationActionTimeout()
                pendingGoalBaselineSequence = null
            }
            _state.update {
                it.copy(
                    navigationAction = if (ok) it.navigationAction else NavigationAction.None,
                    detail = if (ok) "目标请求已受理，等待结构化目标状态" else "导航目标发布失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun cancelNavigation() {
        val current = _state.value
        if (current.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        val goalStateUncertain = current.navigationGoalStateUncertain
        if (
            (pendingGoalBaselineSequence != null ||
                current.navigationAction == NavigationAction.PublishingGoal) &&
            !goalStateUncertain
        ) {
            _state.update { it.copy(error = "目标状态尚未确认，请稍后再取消") }
            return
        }
        if (!goalStateUncertain && current.navigationGoalState.trim().lowercase() !in knownGoalStates) {
            _state.update { it.copy(error = "未收到有效的结构化目标状态，请稍后再取消") }
            return
        }
        if (
            !goalStateUncertain &&
            !isGoalRunning(current.navigationGoalState) &&
            !current.navigationActive
        ) {
            stopNavigationRoute()
            _state.update { it.copy(detail = "当前没有可取消的导航目标", error = "") }
            return
        }
        if (
            !goalStateUncertain &&
            current.navigationActive &&
            !isGoalRunning(current.navigationGoalState)
        ) {
            _state.update { it.copy(error = "导航活动状态与目标状态尚未同步，请稍后再取消") }
            return
        }
        safeStop()
        stopNavigationRoute(preserveUnconfirmedGoal = goalStateUncertain)
        cancelTargetSequence = if (goalStateUncertain) null else current.navigationGoalSequence
        cancelTargetBaselineSequence = if (goalStateUncertain) {
            pendingGoalBaselineSequence ?: current.navigationGoalSequence
        } else {
            null
        }
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationTakeoverPending = false,
                navigationAction = NavigationAction.Cancelling,
                detail = if (goalStateUncertain) {
                    "目标状态不确定，正在发送安全取消请求…"
                } else {
                    "正在取消导航目标…"
                },
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.Cancelling,
            actionToken,
            "取消导航终态确认超时"
        )
        client.requestService("cancel_navigation") { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.Cancelling, actionToken)) {
                return@requestService
            }
            if (!ok) {
                clearNavigationActionTimeout()
                cancelTargetSequence = null
                cancelTargetBaselineSequence = null
            }
            _state.update {
                it.copy(
                    navigationAction = if (ok) it.navigationAction else NavigationAction.None,
                    detail = when {
                        ok -> "取消请求已受理，等待结构化终态"
                        goalStateUncertain -> "取消导航失败；目标状态仍不确定"
                        else -> "取消导航失败"
                    },
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun requestNavigationTakeover() {
        val current = _state.value
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Navigation
        ) {
            _state.update { it.copy(error = "仅可在 Nav2 导航环境中人工接管") }
            return
        }
        if (current.remoteControlArmed) {
            disarmRemoteControl()
            return
        }
        if (current.navigationGoalStateUncertain) {
            _state.update { it.copy(error = "目标状态不确定时只允许取消目标或结束导航环境") }
            return
        }
        if (pendingGoalBaselineSequence != null || current.navigationAction == NavigationAction.PublishingGoal) {
            _state.update { it.copy(error = "目标状态尚未确认，暂不能人工接管") }
            return
        }
        if (current.navigationGoalState.trim().lowercase() !in knownGoalStates) {
            _state.update { it.copy(error = "未收到有效的结构化目标状态，暂不能人工接管") }
            return
        }
        if (current.navigationActive && !isGoalRunning(current.navigationGoalState)) {
            _state.update { it.copy(error = "导航活动状态与目标状态尚未同步，暂不能人工接管") }
            return
        }
        safeStop()
        stopNavigationRoute()
        if (!isGoalRunning(current.navigationGoalState) && !current.navigationActive) {
            activateNavigationTakeover()
            return
        }
        cancelTargetSequence = current.navigationGoalSequence
        cancelTargetBaselineSequence = null
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationTakeoverPending = true,
                navigationAction = NavigationAction.TakingOver,
                detail = "正在取消 Nav2 目标并等待取消完成…",
                error = ""
            )
        }
        startTakeoverZeroGuard()
        scheduleNavigationActionTimeout(
            NavigationAction.TakingOver,
            actionToken,
            "未确认 Nav2 已进入终态，人工接管保持锁定"
        )
        client.requestService("cancel_navigation") { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.TakingOver, actionToken)) {
                return@requestService
            }
            if (!ok) {
                stopTakeoverZeroGuard()
                clearNavigationActionTimeout()
                cancelTargetSequence = null
                cancelTargetBaselineSequence = null
                _state.update {
                    it.copy(
                        navigationAction = NavigationAction.None,
                        navigationTakeoverPending = false,
                        error = message,
                        lastResponse = message
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        detail = "取消请求已受理，等待 Nav2 返回取消状态",
                        lastResponse = message
                    )
                }
            }
        }
    }

    fun stopNavigation() {
        val current = _state.value
        if (current.connectionStatus != ConnectionStatus.Connected) {
            _state.update { it.copy(error = "请先连接小车") }
            return
        }
        val preserveGoalSafetyLock = current.navigationGoalStateUncertain ||
            pendingGoalBaselineSequence != null ||
            current.navigationAction == NavigationAction.PublishingGoal
        safeStop()
        stopNavigationRoute(preserveUnconfirmedGoal = preserveGoalSafetyLock)
        stopTakeoverZeroGuard()
        cancelTargetSequence = null
        cancelTargetBaselineSequence = null
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                remoteControlArmed = false,
                navigationTakeoverPending = false,
                navigationAction = NavigationAction.Stopping,
                navigationGoalStateUncertain = it.navigationGoalStateUncertain ||
                    preserveGoalSafetyLock,
                detail = "正在结束 n1/n3/n4…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.Stopping,
            actionToken,
            "停止导航环境响应超时"
        )
        client.requestService("stop_navigation") { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.Stopping, actionToken)) {
                return@requestService
            }
            clearNavigationActionTimeout()
            if (ok) {
                pendingGoalBaselineSequence = null
                clearNavigationTelemetry(keepMap = true)
            }
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    navigationReady = if (ok) false else it.navigationReady,
                    navigationActive = if (ok) false else it.navigationActive,
                    detail = if (ok) "导航环境已结束，小车已停车" else "停止导航环境失败",
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    fun drive(
        linearX: Double,
        linearY: Double,
        angularZ: Double,
        command: String = "遥控杆"
    ) {
        if (!_state.value.remoteControlArmed) return
        activeDrive = ActiveDrive(
            linearX = linearX.coerceIn(-0.50, 0.50),
            linearY = linearY.coerceIn(-0.50, 0.50),
            angularZ = angularZ.coerceIn(-2.0, 2.0),
            command = command
        )
        sendActiveDriveFrame()
        if (drivePublishJob?.isActive != true && _state.value.remoteControlArmed) {
            drivePublishJob = viewModelScope.launch {
                while (isActive && _state.value.remoteControlArmed) {
                    delay(100)
                    sendActiveDriveFrame()
                }
            }
        }
    }

    fun safeStop() {
        drivePublishJob?.cancel()
        drivePublishJob = null
        activeDrive = ActiveDrive()
        if (client.sendTwist(0.0, 0.0, 0.0)) {
            _state.update {
                it.copy(
                    lastDriveCommand = "停止",
                    sentFrameCount = it.sentFrameCount + 1
                )
            }
        }
    }

    private fun sendActiveDriveFrame() {
        val drive = activeDrive
        val sent = client.sendTwist(
            drive.linearX,
            drive.linearY,
            drive.angularZ
        )
        if (!sent) {
            drivePublishJob?.cancel()
            drivePublishJob = null
            _state.update {
                it.copy(
                    remoteControlArmed = false,
                    error = "遥控指令发送失败，控制已锁定"
                )
            }
        } else {
            _state.update {
                it.copy(
                    lastDriveCommand = drive.command,
                    sentFrameCount = it.sentFrameCount + 1,
                    error = ""
                )
            }
        }
    }

    private fun applySnapshot(snapshot: BridgeSnapshot) {
        val stateBeforeSnapshot = _state.value
        val snapshotConnected = snapshot.connectionStatus == ConnectionStatus.Connected
        val snapshotMode = if (snapshotConnected) {
            snapshot.currentMode ?: stateBeforeSnapshot.currentMode
        } else {
            OperatingMode.Unknown
        }
        val modeLeavingNavigation = snapshotConnected &&
            stateBeforeSnapshot.currentMode == OperatingMode.Navigation &&
            snapshotMode != OperatingMode.Navigation
        val navigationOutputResumedDuringRemoteControl = snapshotConnected &&
            stateBeforeSnapshot.remoteControlArmed &&
            snapshot.navigationActive == true

        if (modeLeavingNavigation || navigationOutputResumedDuringRemoteControl) {
            drivePublishJob?.cancel()
            drivePublishJob = null
            activeDrive = ActiveDrive()
            client.sendTwist(0.0, 0.0, 0.0)
        }
        if (modeLeavingNavigation) {
            stopTakeoverZeroGuard()
            clearNavigationActionTimeout()
            stopNavigationRoute()
            navigationActionToken += 1
            pendingGoalBaselineSequence = null
            cancelTargetSequence = null
            cancelTargetBaselineSequence = null
        }
        if (snapshot.connectionStatus != ConnectionStatus.Connected) {
            drivePublishJob?.cancel()
            drivePublishJob = null
            mappingActionTimeoutJob?.cancel()
            mappingActionTimeoutJob = null
            navigationActionTimeoutJob?.cancel()
            navigationActionTimeoutJob = null
            stopTakeoverZeroGuard()
            stopNavigationRoute()
            navigationActionToken += 1
            pendingGoalBaselineSequence = null
            routeGoalSequence = null
            handledRouteTerminalSequence = null
            cancelTargetSequence = null
            cancelTargetBaselineSequence = null
            activeDrive = ActiveDrive()
        }
        var routeInvalidatedByMap = false
        _state.update { current ->
            val stillConnected = snapshot.connectionStatus == ConnectionStatus.Connected
            val resolvedMode = if (stillConnected) {
                snapshot.currentMode ?: current.currentMode
            } else {
                OperatingMode.Unknown
            }
            val leavingNavigation = current.currentMode == OperatingMode.Navigation &&
                resolvedMode != OperatingMode.Navigation
            val nextMap = snapshot.slamMap ?: current.slamMap
            val nextGlobalCostmap = if (leavingNavigation) {
                null
            } else {
                snapshot.globalCostmap ?: current.globalCostmap
            }
            val selections = if (
                nextMap != null && (snapshot.slamMap != null || snapshot.globalCostmap != null)
            ) {
                NavigationSafetyRules.sanitizeSelections(
                    staticMap = nextMap,
                    globalCostmap = nextGlobalCostmap,
                    initialPose = current.selectedInitialPose,
                    goalPose = current.selectedGoalPose,
                    waypoints = current.navigationWaypoints
                )
            } else {
                null
            }
            val invalidSelectionMessage = selections?.takeIf { it.changed }?.let {
                buildString {
                    append("地图已更新，已清除失效点位")
                    val removed = buildList {
                        if (it.initialPoseRemoved) add("初始位姿")
                        if (it.goalPoseRemoved) add("目标点")
                        if (it.removedWaypointCount > 0) add("${it.removedWaypointCount} 个航点")
                    }
                    if (removed.isNotEmpty()) append("：${removed.joinToString("、")}")
                }
            }
            if ((selections?.removedWaypointCount ?: 0) > 0 && current.navigationRouteActive) {
                routeInvalidatedByMap = true
            }
            current.copy(
                connectionStatus = snapshot.connectionStatus,
                currentMode = resolvedMode,
                runtimePhase = snapshot.runtimePhase ?: current.runtimePhase,
                ready = if (stillConnected) snapshot.ready ?: current.ready else false,
                detail = invalidSelectionMessage ?: snapshot.detail ?: current.detail,
                error = invalidSelectionMessage ?: snapshot.error ?: current.error,
                protocolVersion = snapshot.protocolVersion ?: current.protocolVersion,
                lastResponse = snapshot.response ?: current.lastResponse,
                mappingActive = if (stillConnected) {
                    snapshot.mappingActive ?: current.mappingActive
                } else {
                    false
                },
                navigationReady = if (stillConnected && !leavingNavigation) {
                    snapshot.navigationReady ?: current.navigationReady
                } else {
                    false
                },
                navigationActive = if (stillConnected && !leavingNavigation) {
                    snapshot.navigationActive ?: current.navigationActive
                } else {
                    false
                },
                navigationStatus = if (stillConnected && !leavingNavigation) {
                    snapshot.navigationStatus ?: current.navigationStatus
                } else {
                    "未启动"
                },
                navigationGoalSequence = if (stillConnected && !leavingNavigation) {
                    snapshot.navigationGoalSequence ?: current.navigationGoalSequence
                } else {
                    0L
                },
                navigationGoalState = if (stillConnected && !leavingNavigation) {
                    snapshot.navigationGoalState ?: current.navigationGoalState
                } else {
                    "none"
                },
                navigationResultStatus = if (!stillConnected || leavingNavigation) {
                    null
                } else if (snapshot.navigationResultStatusPresent) {
                    snapshot.navigationResultStatus
                } else {
                    current.navigationResultStatus
                },
                slamMap = nextMap,
                robotPose = snapshot.robotPose ?: current.robotPose,
                laserScan = snapshot.laserScan ?: current.laserScan,
                localCostmap = if (leavingNavigation) null else {
                    snapshot.localCostmap ?: current.localCostmap
                },
                globalCostmap = nextGlobalCostmap,
                globalPath = if (leavingNavigation) null else {
                    snapshot.globalPath ?: current.globalPath
                },
                localPath = if (leavingNavigation) null else {
                    snapshot.localPath ?: current.localPath
                },
                remoteControlArmed = current.remoteControlArmed &&
                    stillConnected &&
                    !leavingNavigation &&
                    snapshot.navigationActive != true,
                mappingAction = if (stillConnected) {
                    current.mappingAction
                } else {
                    MappingAction.None
                },
                navigationAction = if (stillConnected && !leavingNavigation) {
                    current.navigationAction
                } else {
                    NavigationAction.None
                },
                navigationGoalStateUncertain = current.navigationGoalStateUncertain &&
                    stillConnected &&
                    !leavingNavigation,
                navigationTakeoverPending = current.navigationTakeoverPending &&
                    stillConnected &&
                    !leavingNavigation,
                selectedInitialPose = if (selections != null) {
                    selections.initialPose
                } else {
                    current.selectedInitialPose
                },
                selectedGoalPose = if (selections != null) {
                    selections.goalPose
                } else {
                    current.selectedGoalPose
                },
                navigationWaypoints = selections?.waypoints ?: current.navigationWaypoints,
                navigationRouteActive = if (routeInvalidatedByMap) {
                    false
                } else {
                    current.navigationRouteActive
                },
                navigationRouteIndex = if (routeInvalidatedByMap) 0 else current.navigationRouteIndex,
                localOverlayFrame = if (stillConnected) {
                    snapshot.localOverlayFrame ?: current.localOverlayFrame
                } else {
                    ""
                },
                localPlanFrame = if (stillConnected) {
                    snapshot.localPlanFrame ?: current.localPlanFrame
                } else {
                    ""
                }
            )
        }
        if (routeInvalidatedByMap) {
            navigationRouteJob?.cancel()
            navigationRouteJob = null
            routeGoalSequence = null
            handledRouteTerminalSequence = null
        }
        if (snapshot.connectionStatus == ConnectionStatus.Connected) {
            handleStructuredNavigationGoal(snapshot)
        }
    }

    private fun scheduleMappingActionTimeout(action: MappingAction, message: String) {
        mappingActionTimeoutJob?.cancel()
        mappingActionTimeoutJob = viewModelScope.launch {
            delay(12_000)
            if (_state.value.mappingAction == action) {
                _state.update {
                    it.copy(
                        mappingAction = MappingAction.None,
                        error = message,
                        detail = "未收到小车响应"
                    )
                }
            }
        }
    }

    private fun clearMappingActionTimeout() {
        mappingActionTimeoutJob?.cancel()
        mappingActionTimeoutJob = null
    }

    private fun scheduleNavigationActionTimeout(
        action: NavigationAction,
        actionToken: Long,
        message: String
    ) {
        navigationActionTimeoutJob?.cancel()
        navigationActionTimeoutJob = viewModelScope.launch {
            delay(12_000)
            if (isCurrentNavigationAction(action, actionToken)) {
                if (action == NavigationAction.PublishingGoal) {
                    navigationRouteJob?.cancel()
                    navigationRouteJob = null
                    routeGoalSequence = null
                    handledRouteTerminalSequence = null
                    _state.update {
                        it.copy(
                            navigationAction = NavigationAction.None,
                            navigationRouteActive = false,
                            navigationRouteIndex = 0,
                            navigationGoalStateUncertain = true,
                            remoteControlArmed = false,
                            error = "$message：目标可能已被 Nav2 接收",
                            detail = "目标状态不确定；禁止重复发送，只能取消目标或结束导航环境"
                        )
                    }
                    return@launch
                }
                if (action in setOf(NavigationAction.Cancelling, NavigationAction.TakingOver)) {
                    _state.update {
                        it.copy(
                            error = message,
                            detail = "仍在等待小车的结构化终态确认"
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        navigationAction = NavigationAction.None,
                        navigationTakeoverPending = if (action == NavigationAction.TakingOver) {
                            false
                        } else {
                            it.navigationTakeoverPending
                        },
                        error = message,
                        detail = "未收到小车的完成确认"
                    )
                }
            }
        }
    }

    private fun clearNavigationActionTimeout() {
        navigationActionTimeoutJob?.cancel()
        navigationActionTimeoutJob = null
    }

    private fun nextNavigationActionToken(): Long {
        navigationActionToken += 1
        return navigationActionToken
    }

    private fun isCurrentNavigationAction(action: NavigationAction, actionToken: Long): Boolean =
        navigationActionToken == actionToken && _state.value.navigationAction == action

    private fun startTakeoverZeroGuard() {
        takeoverZeroGuardJob?.cancel()
        takeoverZeroGuardJob = viewModelScope.launch {
            while (isActive && _state.value.navigationTakeoverPending) {
                client.sendTwist(0.0, 0.0, 0.0)
                delay(100)
            }
        }
    }

    private fun stopTakeoverZeroGuard() {
        takeoverZeroGuardJob?.cancel()
        takeoverZeroGuardJob = null
    }

    private fun activateNavigationTakeover() {
        val current = _state.value
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Navigation
        ) {
            stopTakeoverZeroGuard()
            clearNavigationActionTimeout()
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    navigationTakeoverPending = false,
                    remoteControlArmed = false,
                    error = "导航环境已变化，人工接管未启用"
                )
            }
            return
        }
        stopTakeoverZeroGuard()
        clearNavigationActionTimeout()
        val zeroSent = client.sendTwist(0.0, 0.0, 0.0)
        _state.update {
            it.copy(
                navigationAction = NavigationAction.None,
                navigationTakeoverPending = false,
                remoteControlArmed = zeroSent,
                detail = if (zeroSent) "Nav2 已停止输出，人工接管已启用" else "零速度发送失败",
                error = if (zeroSent) "" else "人工接管保持锁定"
            )
        }
    }

    private fun clearNavigationTelemetry(keepMap: Boolean = true) {
        _state.update {
            it.copy(
                slamMap = if (keepMap) it.slamMap else null,
                localCostmap = null,
                globalCostmap = null,
                globalPath = null,
                localPath = null,
                navigationReady = false,
                navigationActive = false,
                navigationStatus = "未启动",
                navigationGoalSequence = 0L,
                navigationGoalState = "none",
                navigationResultStatus = null,
                navigationGoalStateUncertain = false,
                localOverlayFrame = "",
                localPlanFrame = ""
            )
        }
    }

    private fun sendRouteWaypoint(index: Int) {
        val current = _state.value
        val goal = current.navigationWaypoints.getOrNull(index)
        if (!current.navigationRouteActive || goal == null) return
        if (
            current.connectionStatus != ConnectionStatus.Connected ||
            current.currentMode != OperatingMode.Navigation ||
            !(current.ready && current.navigationReady) ||
            current.navigationActive ||
            current.navigationGoalStateUncertain ||
            current.navigationAction != NavigationAction.None ||
            pendingGoalBaselineSequence != null ||
            isGoalRunning(current.navigationGoalState)
        ) {
            stopNavigationRoute()
            _state.update { it.copy(error = "导航状态已变化，路线已停止，未发送下一个航点") }
            return
        }
        val validationError = navigationPoseValidationError(current, goal, "航点 ${index + 1}")
        if (validationError != null) {
            val validated = validatedNavigationSelections(current)
            stopNavigationRoute()
            _state.update {
                it.copy(
                    navigationWaypoints = validated?.waypoints ?: emptyList(),
                    error = "$validationError；路线已停止"
                )
            }
            return
        }
        pendingGoalBaselineSequence = current.navigationGoalSequence
        routeGoalSequence = null
        val actionToken = nextNavigationActionToken()
        _state.update {
            it.copy(
                navigationAction = NavigationAction.PublishingGoal,
                navigationRouteIndex = index,
                detail = "正在发送航点 ${index + 1}/${it.navigationWaypoints.size}…",
                error = ""
            )
        }
        scheduleNavigationActionTimeout(
            NavigationAction.PublishingGoal,
            actionToken,
            "航点 ${index + 1} 状态确认超时"
        )
        client.requestPose("goal_pose", goal.x, goal.y, goal.yaw) { ok, message ->
            if (!isCurrentNavigationAction(NavigationAction.PublishingGoal, actionToken)) {
                return@requestPose
            }
            if (!ok) {
                clearNavigationActionTimeout()
                pendingGoalBaselineSequence = null
                stopNavigationRoute()
            }
            _state.update {
                it.copy(
                    navigationAction = if (ok) it.navigationAction else NavigationAction.None,
                    detail = if (ok) {
                        "航点 ${index + 1}/${it.navigationWaypoints.size} 请求已受理，等待结构化目标状态"
                    } else {
                        "航点 ${index + 1} 发布失败"
                    },
                    error = if (ok) "" else message,
                    lastResponse = message
                )
            }
        }
    }

    private fun handleStructuredNavigationGoal(snapshot: BridgeSnapshot) {
        val goalState = snapshot.navigationGoalState?.trim()?.lowercase() ?: return
        if (goalState !in knownGoalStates) return
        val sequence = snapshot.navigationGoalSequence ?: _state.value.navigationGoalSequence
        val baseline = pendingGoalBaselineSequence

        if (
            baseline != null &&
            sequence > baseline &&
            (isGoalRunning(goalState) || isGoalTerminal(goalState))
        ) {
            pendingGoalBaselineSequence = null
            if (cancelTargetBaselineSequence != null) cancelTargetSequence = sequence
            if (_state.value.navigationRouteActive) routeGoalSequence = sequence
            if (_state.value.navigationAction == NavigationAction.PublishingGoal) {
                clearNavigationActionTimeout()
                navigationActionToken += 1
                _state.update {
                    it.copy(
                        navigationAction = NavigationAction.None,
                        detail = if (it.navigationRouteActive) {
                            "航点 ${it.navigationRouteIndex + 1}/${it.navigationWaypoints.size} 已进入 $goalState"
                        } else {
                            "导航目标已进入 $goalState"
                        },
                        error = ""
                    )
                }
            } else if (_state.value.navigationGoalStateUncertain) {
                _state.update {
                    it.copy(
                        navigationGoalStateUncertain = false,
                        detail = "已恢复目标状态：$goalState",
                        error = ""
                    )
                }
            }
        }

        val currentAction = _state.value.navigationAction
        val targetSequence = cancelTargetSequence
        val targetBaseline = cancelTargetBaselineSequence
        val uncertainCancellationReachedEnd = targetBaseline != null &&
            ((goalState == "none" && sequence > targetBaseline) ||
                (isGoalTerminal(goalState) && sequence > targetBaseline))
        val cancellationReachedEnd =
            (goalState == "none" || isGoalTerminal(goalState)) &&
                ((targetSequence != null && sequence == targetSequence) ||
                    uncertainCancellationReachedEnd)
        if (cancellationReachedEnd && currentAction == NavigationAction.Cancelling) {
            clearNavigationActionTimeout()
            navigationActionToken += 1
            cancelTargetSequence = null
            cancelTargetBaselineSequence = null
            pendingGoalBaselineSequence = null
            _state.update {
                it.copy(
                    navigationAction = NavigationAction.None,
                    navigationGoalStateUncertain = false,
                    detail = "导航目标已结束：$goalState",
                    error = ""
                )
            }
        } else if (cancellationReachedEnd && currentAction == NavigationAction.TakingOver) {
            navigationActionToken += 1
            cancelTargetSequence = null
            cancelTargetBaselineSequence = null
            activateNavigationTakeover()
        }

        handleNavigationRouteGoalState(sequence, goalState, snapshot.navigationResultStatus)
    }

    private fun handleNavigationRouteGoalState(
        sequence: Long,
        goalState: String,
        resultStatus: Int?
    ) {
        val current = _state.value
        if (!current.navigationRouteActive) return
        if (routeGoalSequence != sequence || !isGoalTerminal(goalState)) return
        if (handledRouteTerminalSequence == sequence) return
        handledRouteTerminalSequence = sequence
        when (goalState) {
            "succeeded" -> {
                val nextIndex = current.navigationRouteIndex + 1
                if (nextIndex >= current.navigationWaypoints.size) {
                    navigationRouteJob?.cancel()
                    navigationRouteJob = null
                    _state.update {
                        it.copy(
                            navigationRouteActive = false,
                            detail = "多航点路线已完成，共 ${it.navigationWaypoints.size} 个航点",
                            error = ""
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            navigationRouteIndex = nextIndex,
                            detail = "航点 ${nextIndex}/${it.navigationWaypoints.size} 已完成，准备下一个航点"
                        )
                    }
                    navigationRouteJob?.cancel()
                    navigationRouteJob = viewModelScope.launch {
                        delay(700)
                        sendRouteWaypoint(nextIndex)
                    }
                }
            }

            "canceled" -> {
                stopNavigationRoute()
                _state.update { it.copy(detail = "多航点路线已取消") }
            }

            else -> {
                stopNavigationRoute()
                _state.update {
                    it.copy(
                        detail = "多航点路线已停止",
                        error = buildString {
                            append("航点终态：")
                            append(goalState)
                            resultStatus?.let { append("（状态码 $it）") }
                        }
                    )
                }
            }
        }
    }

    private fun stopNavigationRoute(preserveUnconfirmedGoal: Boolean = false) {
        navigationRouteJob?.cancel()
        navigationRouteJob = null
        if (!preserveUnconfirmedGoal) pendingGoalBaselineSequence = null
        routeGoalSequence = null
        handledRouteTerminalSequence = null
        if (_state.value.navigationRouteActive) {
            _state.update {
                it.copy(
                    navigationRouteActive = false,
                    navigationRouteIndex = 0
                )
            }
        }
    }

    private fun validatedNavigationSelections(current: CarUiState) = current.slamMap?.let { map ->
        NavigationSafetyRules.sanitizeSelections(
            staticMap = map,
            globalCostmap = current.globalCostmap,
            initialPose = current.selectedInitialPose,
            goalPose = current.selectedGoalPose,
            waypoints = current.navigationWaypoints
        )
    }

    private fun navigationPoseValidationError(
        current: CarUiState,
        pose: RobotPose,
        label: String
    ): String? {
        if (current.slamMap == null) return "$label 复验失败：尚未收到静态地图"
        if (!NavigationSafetyRules.isPoseSelectable(current.slamMap, null, pose)) {
            return "$label 已不在静态地图的自由区域，已拒绝发送"
        }
        if (
            current.globalCostmap != null &&
            !NavigationSafetyRules.isPoseSelectable(
                current.slamMap,
                current.globalCostmap,
                pose
            )
        ) {
            return "$label 已不在全局代价地图的自由区域，已拒绝发送"
        }
        return null
    }

    private fun isGoalRunning(goalState: String): Boolean =
        goalState.trim().lowercase() in runningGoalStates

    private fun isGoalTerminal(goalState: String): Boolean =
        goalState.trim().lowercase() in terminalGoalStates

    private fun normalizeYaw(value: Float): Float {
        var result = value
        val fullTurn = (Math.PI * 2.0).toFloat()
        while (result > Math.PI.toFloat()) result -= fullTurn
        while (result < -Math.PI.toFloat()) result += fullTurn
        return result
    }

    override fun onCleared() {
        safeStop()
        clearMappingActionTimeout()
        clearNavigationActionTimeout()
        stopTakeoverZeroGuard()
        stopNavigationRoute()
        client.disconnect()
        super.onCleared()
    }
}
