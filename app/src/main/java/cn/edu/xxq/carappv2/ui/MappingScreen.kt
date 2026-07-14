package cn.edu.xxq.carappv2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
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
import cn.edu.xxq.carappv2.model.MappingAction
import cn.edu.xxq.carappv2.model.OperatingMode

@Composable
internal fun MappingScreen(state: CarUiState, viewModel: CarViewModel) {
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val mappingMode = state.currentMode == OperatingMode.Mapping
    val mappingReady = connected &&
        mappingMode &&
        state.ready &&
        state.slamMap != null
    val actionPending = state.mappingAction != MappingAction.None
    val armed = connected && state.remoteControlArmed
    var showQuickControls by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MappingRuntimeCard(state)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "建图任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::startMapping,
                        enabled = connected && !mappingMode && !actionPending,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("启动 m1")
                    }
                    Button(
                        onClick = { showSaveConfirm = true },
                        enabled = mappingReady && !actionPending,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存 m4")
                    }
                    Button(
                        onClick = viewModel::stopMapping,
                        enabled = connected && mappingMode && !actionPending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("结束建图")
                    }
                }
                Text(
                    "m1 会启动底盘、雷达和 GMapping；m4 当前表示保存命令已受理，" +
                        "地图覆盖为 yahboomcar.pgm / yahboomcar.yaml。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF526579)
                )
                if (state.lastResponse != "-") {
                    Text(
                        state.lastResponse,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
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
                            "地图可视化（m2）",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        val map = state.slamMap
                        Text(
                            if (map == null) {
                                "等待 /map 数据"
                            } else {
                                map.width.toString() + " × " + map.height +
                                    " · " + "%.3f".format(map.resolution) + " m/格"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF526579)
                        )
                    }
                    Text(
                        "双指缩放 · 单指拖动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                SlamMapCanvas(
                    map = state.slamMap,
                    pose = state.robotPose,
                    scan = state.laserScan,
                    modifier = Modifier.fillMaxWidth()
                )

                val pose = state.robotPose
                Text(
                    if (pose == null) {
                        "位姿：等待 TF / odom"
                    } else {
                        "位姿：x=" + "%.2f".format(pose.x) + " m，y=" +
                            "%.2f".format(pose.y) + " m，雷达点=" +
                            (state.laserScan?.sampleCount ?: 0)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
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
                        Text(
                            "建图控制（m3）",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                armed -> "10 Hz 持续发送，松手停车"
                                mappingReady -> "地图已就绪，可以启用遥控"
                                else -> "等待 m1 进入 mapping / ready"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            if (armed) {
                                viewModel.disarmRemoteControl()
                            } else {
                                viewModel.armMappingControl()
                            }
                        },
                        enabled = mappingReady && !actionPending
                    ) {
                        Text(if (armed) "停用 m3" else "启用 m3")
                    }
                }

                Text(
                    "上推前进 · 下拉后退 · 左右旋转",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
                MappingSpeedControls(state, viewModel)
                M3Joystick(
                    enabled = armed,
                    onDrive = { forward, turn ->
                        viewModel.drive(
                            linearX = forward * state.mappingLinearSpeed,
                            linearY = 0.0,
                            angularZ = -turn * state.mappingAngularSpeed,
                            command = "建图摇杆"
                        )
                    },
                    onStop = viewModel::safeStop,
                    joystickSize = 210.dp
                )
                Text(
                    "命令：" + state.lastDriveCommand +
                        "  ·  已发送 " + state.sentFrameCount + " 帧",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF526579)
                )
                TextButton(onClick = { showQuickControls = !showQuickControls }) {
                    Text(if (showQuickControls) "收起快捷按键" else "展开快捷按键")
                }
                if (showQuickControls) {
                    M3QuickControls(
                        enabled = armed,
                        viewModel = viewModel,
                        linearSpeed = state.mappingLinearSpeed,
                        angularSpeed = state.mappingAngularSpeed
                    )
                }
            }
        }
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text("覆盖保存地图？") },
            text = {
                Text(
                    "m4 将以 yahboomcar 为固定名称保存，并可能覆盖已有的 " +
                        "yahboomcar.pgm 与 yahboomcar.yaml。请先停车并确认地图完整。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSaveConfirm = false
                        viewModel.saveMap()
                    }
                ) {
                    Text("确认保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun MappingSpeedControls(state: CarUiState, viewModel: CarViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("线速度", fontWeight = FontWeight.SemiBold)
                Text(
                    "  %.2f m/s".format(state.mappingLinearSpeed),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  范围 0.05–0.50",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF526579)
                )
            }
            Slider(
                value = state.mappingLinearSpeed.toFloat(),
                onValueChange = { viewModel.updateMappingLinearSpeed(it.toDouble()) },
                valueRange = 0.05f..0.50f,
                steps = 8
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("角速度", fontWeight = FontWeight.SemiBold)
                Text(
                    "  %.1f rad/s".format(state.mappingAngularSpeed),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "  范围 0.2–2.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF526579)
                )
            }
            Slider(
                value = state.mappingAngularSpeed.toFloat(),
                onValueChange = { viewModel.updateMappingAngularSpeed(it.toDouble()) },
                valueRange = 0.20f..2.00f,
                steps = 8
            )
            Text(
                "调整速度时若摇杆仍有输出，App 会先发送零速度。",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF526579)
            )
        }
    }
}

@Composable
private fun MappingRuntimeCard(state: CarUiState) {
    val (label, color) = mappingStatus(state)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
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
                        "SLAM 建图",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        mappingPhaseText(state.runtimePhase),
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
            val showProgress = state.mappingAction != MappingAction.None ||
                (state.currentMode == OperatingMode.Mapping && !state.ready)
            if (showProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

private fun mappingStatus(state: CarUiState): Pair<String, Color> = when {
    state.connectionStatus != ConnectionStatus.Connected ->
        "未连接" to Color(0xFF687687)
    state.mappingAction == MappingAction.Starting ->
        "启动中" to Color(0xFFB25D00)
    state.mappingAction == MappingAction.Saving ->
        "保存中" to Color(0xFFB25D00)
    state.mappingAction == MappingAction.Stopping ->
        "停止中" to Color(0xFFB25D00)
    state.currentMode == OperatingMode.Mapping && state.ready ->
        "建图中" to Color(0xFF16835A)
    state.currentMode == OperatingMode.Mapping ->
        "准备中" to Color(0xFFB25D00)
    else -> "未启动" to Color(0xFF687687)
}

private fun mappingPhaseText(phase: String): String = when (phase) {
    "starting" -> "正在启动 m1"
    "checking" -> "正在检查已有 GMapping"
    "waiting_node" -> "等待 slam_gmapping 节点"
    "waiting_scan" -> "等待雷达 /scan"
    "waiting_tf" -> "等待 base_link → laser TF"
    "waiting_map" -> "等待第一帧 /map"
    "ready" -> "GMapping 已就绪，地图正在更新"
    "failed" -> "建图启动失败"
    "idle" -> "尚未开始建图"
    else -> "当前阶段：" + phase
}
