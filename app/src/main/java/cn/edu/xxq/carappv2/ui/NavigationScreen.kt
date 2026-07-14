package cn.edu.xxq.carappv2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.xxq.carappv2.CarViewModel
import cn.edu.xxq.carappv2.model.CarUiState
import cn.edu.xxq.carappv2.model.ConnectionStatus
import cn.edu.xxq.carappv2.model.NavigationAction
import cn.edu.xxq.carappv2.model.NavigationPlanner
import cn.edu.xxq.carappv2.model.NavigationSafetyRules
import cn.edu.xxq.carappv2.model.OperatingMode
import cn.edu.xxq.carappv2.model.RobotPose

private enum class NavigationSelectionMode {
    None,
    InitialPose,
    Goal
}

@Composable
internal fun NavigationScreen(state: CarUiState, viewModel: CarViewModel) {
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val baseMode = state.currentMode == OperatingMode.NavigationBase
    val navigationMode = state.currentMode == OperatingMode.Navigation
    val actionPending = state.navigationAction != NavigationAction.None
    var selectionModeName by rememberSaveable {
        mutableStateOf(NavigationSelectionMode.None.name)
    }
    val selectionMode = runCatching {
        NavigationSelectionMode.valueOf(selectionModeName)
    }.getOrDefault(NavigationSelectionMode.None)
    var showStopConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NavigationRuntimeCard(state)
        NavigationSetupCard(
            state = state,
            viewModel = viewModel,
            connected = connected,
            baseMode = baseMode,
            navigationMode = navigationMode,
            actionPending = actionPending,
            onStop = { showStopConfirm = true }
        )
        NavigationMapCard(
            state = state,
            viewModel = viewModel,
            selectionMode = selectionMode,
            onSelectionMode = { selectionModeName = it.name },
            actionPending = actionPending
        )
        NavigationControlCard(
            state = state,
            viewModel = viewModel,
            navigationMode = navigationMode,
            actionPending = actionPending
        )
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("结束整个导航环境？") },
            text = {
                Text("将先停车并取消目标，然后停止 n3/n4 与 n1。结束后底盘不再接受人工接管。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirm = false
                        viewModel.stopNavigation()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认结束")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun NavigationRuntimeCard(state: CarUiState) {
    val (label, color) = navigationStatusBadge(state)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Nav2 自动导航",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        navigationPhaseText(state.currentMode, state.runtimePhase),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Surface(
                    color = color,
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (
                state.navigationAction != NavigationAction.None ||
                (state.currentMode in setOf(OperatingMode.NavigationBase, OperatingMode.Navigation) &&
                    !state.ready)
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                state.navigationStatus,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.navigationGoalStateUncertain) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        "目标可能已被 Nav2 接收。禁止重复发送；请取消当前目标或结束整个导航环境。",
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                if (state.error.isNotBlank()) state.error else state.detail,
                color = if (state.error.isNotBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NavigationSetupCard(
    state: CarUiState,
    viewModel: CarViewModel,
    connected: Boolean,
    baseMode: Boolean,
    navigationMode: Boolean,
    actionPending: Boolean,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("导航启动流程", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "连接 9092/读取地图 → n1 → n3 或 n4 → 初始位姿 → 目标点",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF526579)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::loadSavedMap,
                    enabled = connected && !navigationMode && !actionPending,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("1 读取地图")
                }
                Button(
                    onClick = viewModel::startNavigationBase,
                    enabled = connected &&
                        state.currentMode != OperatingMode.Mapping &&
                        !navigationMode &&
                        !actionPending,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("2 启动 n1")
                }
            }

            Text("3 选择互斥算法", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NavigationPlanner.entries.forEach { planner ->
                    FilterChip(
                        selected = state.selectedNavigationPlanner == planner,
                        onClick = { viewModel.selectNavigationPlanner(planner) },
                        enabled = !state.navigationActive && !actionPending,
                        label = { Text(planner.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::startSelectedNavigation,
                    enabled = connected &&
                        ((baseMode && state.ready) || (navigationMode && !state.navigationActive)) &&
                        !actionPending,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("启动 ${state.selectedNavigationPlanner.label}")
                }
                Button(
                    onClick = onStop,
                    enabled = connected &&
                        (baseMode || navigationMode) &&
                        state.navigationAction != NavigationAction.Stopping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("结束导航环境")
                }
            }
        }
    }
}

@Composable
private fun NavigationMapCard(
    state: CarUiState,
    viewModel: CarViewModel,
    selectionMode: NavigationSelectionMode,
    onSelectionMode: (NavigationSelectionMode) -> Unit,
    actionPending: Boolean
) {
    val showLocalCostmap = state.localOverlayFrame.equals("map", ignoreCase = true)
    val showLocalPath = state.localPlanFrame.equals("map", ignoreCase = true)
    val hasHiddenLocalOverlay =
        (state.localCostmap != null && !showLocalCostmap) ||
            (state.localPath != null && !showLocalPath)
    val localPathLabel = when {
        state.localPath == null -> "0"
        showLocalPath -> state.localPath.pointCount.toString()
        else -> "已隐藏"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "导航地图（替代 n2）",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "蓝=全局路径 · 橙=全局代价地图；局部数据仅在 map 坐标系下显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF526579)
                    )
                }
                Text(
                    "全局 ${state.globalPath?.pointCount ?: 0} 点\n局部 $localPathLabel${if (localPathLabel == "已隐藏") "" else " 点"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (hasHiddenLocalOverlay) {
                Surface(
                    color = Color(0xFFFFF3E0),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        "局部代价地图/路径处于 odom 或缺少坐标系标记，已停止叠加，避免在地图上显示错位。",
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        color = Color(0xFF8A4B00),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            NavigationMapCanvas(
                map = state.slamMap,
                robotPose = state.robotPose,
                scan = state.laserScan,
                globalCostmap = state.globalCostmap,
                localCostmap = state.localCostmap.takeIf { showLocalCostmap },
                globalPath = state.globalPath,
                localPath = state.localPath.takeIf { showLocalPath },
                initialPose = state.selectedInitialPose,
                goalPose = state.selectedGoalPose,
                waypoints = state.navigationWaypoints,
                selectionEnabled = selectionMode != NavigationSelectionMode.None &&
                    (selectionMode != NavigationSelectionMode.InitialPose ||
                        !NavigationSafetyRules.hasInitialPoseConflict(state)),
                selectionHint = when (selectionMode) {
                    NavigationSelectionMode.InitialPose -> "点击地图选择初始位置"
                    NavigationSelectionMode.Goal -> "点击地图选择目标位置"
                    NavigationSelectionMode.None -> ""
                },
                onMapTap = { x, y ->
                    when (selectionMode) {
                        NavigationSelectionMode.InitialPose -> viewModel.selectInitialPose(x, y)
                        NavigationSelectionMode.Goal -> viewModel.selectNavigationGoal(x, y)
                        NavigationSelectionMode.None -> Unit
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSelectionMode(
                            if (selectionMode == NavigationSelectionMode.InitialPose) {
                                NavigationSelectionMode.None
                            } else {
                                NavigationSelectionMode.InitialPose
                            }
                        )
                    },
                    enabled = state.slamMap != null &&
                        !actionPending &&
                        !NavigationSafetyRules.hasInitialPoseConflict(state),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选择初始位姿")
                }
                Button(
                    onClick = {
                        onSelectionMode(
                            if (selectionMode == NavigationSelectionMode.Goal) {
                                NavigationSelectionMode.None
                            } else {
                                NavigationSelectionMode.Goal
                            }
                        )
                    },
                    enabled = state.slamMap != null && !actionPending,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选择目标点")
                }
            }

            state.selectedInitialPose?.let { pose ->
                NavigationPoseEditor(
                    title = "初始位姿",
                    pose = pose,
                    buttonText = "发布 /initialpose",
                    enabled = state.currentMode == OperatingMode.Navigation &&
                        state.runtimePhase in setOf(
                            "awaiting_initial_pose",
                            "waiting_local_costmap",
                            "waiting_global_costmap",
                            "waiting_action",
                            "ready"
                        ) &&
                        !actionPending &&
                        !NavigationSafetyRules.hasInitialPoseConflict(state),
                    onYawChange = viewModel::updateInitialPoseYaw,
                    onPublish = viewModel::publishInitialPose
                )
            }
            state.selectedGoalPose?.let { pose ->
                NavigationPoseEditor(
                    title = "导航目标",
                    pose = pose,
                    buttonText = "发送目标",
                    enabled = state.currentMode == OperatingMode.Navigation &&
                        state.ready &&
                        state.navigationReady &&
                        !NavigationSafetyRules.hasGoalDispatchConflict(
                            state,
                            hasUnconfirmedGoalRequest = false
                        ),
                    onYawChange = viewModel::updateNavigationGoalYaw,
                    onPublish = viewModel::publishNavigationGoal
                )
                TextButton(
                    onClick = viewModel::addNavigationWaypoint,
                    enabled = !state.navigationRouteActive &&
                        !actionPending &&
                        !state.navigationGoalStateUncertain,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("把当前目标加入多航点路线")
                }
            }
            if (state.navigationWaypoints.isNotEmpty()) {
                Surface(
                    color = Color(0xFFF4ECFA),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (state.navigationRouteActive) {
                                "多航点路线：正在执行 ${state.navigationRouteIndex + 1}/${state.navigationWaypoints.size}"
                            } else {
                                "多航点路线：${state.navigationWaypoints.size} 个航点"
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            state.navigationWaypoints.mapIndexed { index, point ->
                                "${index + 1}. (%.2f, %.2f)".format(point.x, point.y)
                            }.joinToString("  "),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = viewModel::startWaypointRoute,
                                enabled = state.currentMode == OperatingMode.Navigation &&
                                    state.ready &&
                                    state.navigationReady &&
                                    !state.navigationActive &&
                                    !state.navigationRouteActive &&
                                    !actionPending &&
                                    !state.navigationGoalStateUncertain,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("开始多点导航")
                            }
                            TextButton(
                                onClick = viewModel::clearNavigationWaypoints,
                                enabled = !state.navigationRouteActive && !actionPending,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("清空航点")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationPoseEditor(
    title: String,
    pose: RobotPose,
    buttonText: String,
    enabled: Boolean,
    onYawChange: (Float) -> Unit,
    onPublish: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    "x=%.2f  y=%.2f  朝向=%.0f°".format(
                        pose.x,
                        pose.y,
                        Math.toDegrees(pose.yaw.toDouble())
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Slider(
                value = Math.toDegrees(pose.yaw.toDouble()).toFloat(),
                onValueChange = { onYawChange(Math.toRadians(it.toDouble()).toFloat()) },
                valueRange = -180f..180f,
                steps = 71
            )
            Button(onClick = onPublish, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun NavigationControlCard(
    state: CarUiState,
    viewModel: CarViewModel,
    navigationMode: Boolean,
    actionPending: Boolean
) {
    val armed = state.connectionStatus == ConnectionStatus.Connected && state.remoteControlArmed
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("取消与人工接管", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            state.navigationTakeoverPending -> "持续发送零速度，等待 Nav2 确认取消"
                            armed -> "人工接管中：Nav2 目标已取消"
                            state.navigationActive -> "Nav2 正在执行目标"
                            else -> "无活动目标"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::cancelNavigation,
                    enabled = navigationMode && !actionPending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("取消当前目标")
                }
                Button(
                    onClick = viewModel::requestNavigationTakeover,
                    enabled = navigationMode &&
                        !actionPending &&
                        !state.navigationGoalStateUncertain,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (armed) "结束人工接管" else "请求人工接管")
                }
            }

            if (armed) {
                Text(
                    "接管速度：%.2f m/s · %.1f rad/s".format(
                        state.mappingLinearSpeed,
                        state.mappingAngularSpeed
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = state.mappingLinearSpeed.toFloat(),
                    onValueChange = { viewModel.updateMappingLinearSpeed(it.toDouble()) },
                    valueRange = 0.05f..0.50f,
                    steps = 8
                )
                Slider(
                    value = state.mappingAngularSpeed.toFloat(),
                    onValueChange = { viewModel.updateMappingAngularSpeed(it.toDouble()) },
                    valueRange = 0.20f..2.00f,
                    steps = 8
                )
                M3Joystick(
                    enabled = true,
                    onDrive = { forward, turn ->
                        viewModel.drive(
                            linearX = forward * state.mappingLinearSpeed,
                            linearY = 0.0,
                            angularZ = -turn * state.mappingAngularSpeed,
                            command = "导航人工接管"
                        )
                    },
                    onStop = viewModel::safeStop,
                    joystickSize = 200.dp
                )
            }
        }
    }
}

private fun navigationStatusBadge(state: CarUiState): Pair<String, Color> = when {
    state.connectionStatus != ConnectionStatus.Connected -> "未连接" to Color(0xFF687687)
    state.navigationGoalStateUncertain -> "目标状态不确定" to Color(0xFFD32F2F)
    state.navigationAction != NavigationAction.None -> "处理中" to Color(0xFFB25D00)
    state.remoteControlArmed -> "人工接管" to Color(0xFF6A1B9A)
    state.navigationActive -> "导航中" to Color(0xFF16835A)
    state.currentMode == OperatingMode.Navigation && state.ready -> "Nav2 就绪" to Color(0xFF16835A)
    state.currentMode == OperatingMode.NavigationBase && state.ready -> "n1 就绪" to Color(0xFF1565C0)
    state.currentMode in setOf(OperatingMode.NavigationBase, OperatingMode.Navigation) ->
        "准备中" to Color(0xFFB25D00)
    else -> "未启动" to Color(0xFF687687)
}

private fun navigationPhaseText(mode: OperatingMode, phase: String): String = when (phase) {
    "starting" -> if (mode == OperatingMode.NavigationBase) "正在启动 n1" else "正在启动 Nav2"
    "checking" -> "正在检查已有导航节点"
    "waiting_odom" -> "等待底盘 /odom"
    "waiting_scan" -> "等待雷达 /scan"
    "waiting_tf" -> "等待 base_link → laser TF"
    "waiting_nodes" -> "等待 Nav2 核心节点"
    "waiting_localizer" -> "等待 AMCL 定位节点"
    "awaiting_initial_pose" -> "请在地图上设置初始位姿（此时 ready=false 正常）"
    "waiting_local_costmap" -> "等待局部代价地图"
    "waiting_global_costmap" -> "等待全局代价地图"
    "waiting_action" -> "等待 NavigateToPose Action Server"
    "ready" -> if (mode == OperatingMode.NavigationBase) "n1 已就绪" else "Nav2 已就绪"
    "failed" -> "导航启动失败"
    "idle" -> "尚未启动导航"
    else -> "当前阶段：$phase"
}
