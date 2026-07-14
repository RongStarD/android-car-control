package cn.edu.xxq.carappv2.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.xxq.carappv2.CarViewModel
import cn.edu.xxq.carappv2.model.CarUiState
import cn.edu.xxq.carappv2.model.ConnectionStatus
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

private val AppColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8FF),
    secondary = Color(0xFF0277BD),
    background = Color(0xFFF4F8FE),
    surface = Color.White,
    error = Color(0xFFC62828)
)

private val NavyControl = Color(0xFF334A62)
private val BlueControl = Color(0xFF087F8C)

private enum class AppSection(val label: String) {
    Remote("独立遥控"),
    Mapping("SLAM 建图"),
    Navigation("自动导航")
}

@Composable
fun CarApp(viewModel: CarViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var section by rememberSaveable { mutableStateOf(AppSection.Remote) }
    val pageScrollState = rememberScrollState()

    LaunchedEffect(section) {
        pageScrollState.scrollTo(0)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.disarmRemoteControl()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme(colorScheme = AppColors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                EmergencyStopBar(
                    enabled = state.connectionStatus == ConnectionStatus.Connected,
                    onClick = viewModel::emergencyStop
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(pageScrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Header(state, section)
                ConnectionCard(state, viewModel)
                TabRow(selectedTabIndex = section.ordinal) {
                    AppSection.entries.forEach { destination ->
                        Tab(
                            selected = section == destination,
                            onClick = {
                                if (section != destination) {
                                    viewModel.disarmRemoteControl()
                                    section = destination
                                }
                            },
                            text = { Text(destination.label) }
                        )
                    }
                }
                when (section) {
                    AppSection.Remote -> {
                        RemoteControlCard(state, viewModel)
                        ControlStatusCard(state)
                    }

                    AppSection.Mapping -> MappingScreen(state, viewModel)
                    AppSection.Navigation -> NavigationScreen(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun Header(state: CarUiState, section: AppSection) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "智能小车 V2",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                when (section) {
                    AppSection.Remote -> "m3 独立遥控 · 视频暂缓"
                    AppSection.Mapping -> "m1–m4 SLAM 建图流程"
                    AppSection.Navigation -> "n1–n4 Nav2 自动导航"
                },
                color = MaterialTheme.colorScheme.primary
            )
        }
        ConnectionBadge(state.connectionStatus)
    }
}

@Composable
private fun ConnectionCard(state: CarUiState, viewModel: CarViewModel) {
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
                "连接小车",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.endpoint.host,
                    onValueChange = viewModel::updateHost,
                    enabled = state.connectionStatus == ConnectionStatus.Disconnected,
                    singleLine = true,
                    label = { Text("Jetson IP") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.endpoint.port,
                    onValueChange = viewModel::updatePort,
                    enabled = state.connectionStatus == ConnectionStatus.Disconnected,
                    singleLine = true,
                    label = { Text("端口") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(92.dp)
                )
            }
            Button(
                onClick = {
                    if (state.connectionStatus == ConnectionStatus.Connected) {
                        viewModel.disconnect()
                    } else {
                        viewModel.connect()
                    }
                },
                enabled = state.connectionStatus != ConnectionStatus.Connecting,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    when (state.connectionStatus) {
                        ConnectionStatus.Disconnected -> "连接"
                        ConnectionStatus.Connecting -> "连接中…"
                        ConnectionStatus.Connected -> "断开"
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun RemoteControlCard(state: CarUiState, viewModel: CarViewModel) {
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val armed = connected && state.remoteControlArmed

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "独立遥控",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            armed -> "遥控已启用：10 Hz 持续发送，松手停车"
                            connected -> "已连接，可直接启用 m3 遥控"
                            else -> "请先连接小车"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = {
                        if (armed) {
                            viewModel.disarmRemoteControl()
                        } else {
                            viewModel.armRemoteControl()
                        }
                    },
                    enabled = connected
                ) {
                    Text(if (armed) "停用遥控" else "启用遥控")
                }
            }

            Text(
                "上推前进 · 下拉后退 · 左右旋转",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge
            )

            M3Joystick(
                enabled = armed,
                onDrive = { forward, turn ->
                    viewModel.drive(
                        linearX = forward * 0.20,
                        linearY = 0.0,
                        angularZ = -turn,
                        command = "遥控杆"
                    )
                },
                onStop = viewModel::safeStop
            )

            Text(
                "命令：${state.lastDriveCommand}  ·  已发送 ${state.sentFrameCount} 帧",
                color = Color(0xFF526579),
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "快捷按键（按住移动，松手停止）",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            M3QuickControls(armed, viewModel)
        }
    }
}

@Composable
internal fun M3Joystick(
    enabled: Boolean,
    onDrive: (forward: Double, turn: Double) -> Unit,
    onStop: () -> Unit,
    joystickSize: Dp = 264.dp
) {
    var vector by remember { mutableStateOf(Offset.Zero) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(joystickSize)
                .pointerInput(enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!enabled) return@awaitEachGesture

                        fun updateVector(pointer: Offset) {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val maxTravel = min(size.width, size.height) * 0.31f
                            val delta = pointer - center
                            val distance = sqrt(
                                delta.x * delta.x + delta.y * delta.y
                            )
                            val clamped = if (distance > maxTravel) {
                                delta * (maxTravel / distance)
                            } else {
                                delta
                            }
                            vector = Offset(
                                x = clamped.x / maxTravel,
                                y = clamped.y / maxTravel
                            )
                            onDrive(
                                applyDeadZone(-vector.y),
                                applyDeadZone(vector.x)
                            )
                        }

                        try {
                            updateVector(down.position)
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull {
                                    it.id == down.id
                                }
                                if (change == null || !change.pressed) {
                                    pressed = false
                                } else {
                                    updateVector(change.position)
                                    change.consume()
                                }
                            }
                        } finally {
                            vector = Offset.Zero
                            onStop()
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseRadius = size.minDimension / 2f - 3.dp.toPx()
                val maxTravel = size.minDimension * 0.31f
                val knobCenter = center + Offset(
                    vector.x * maxTravel,
                    vector.y * maxTravel
                )
                val knobRadius = size.minDimension * 0.15f

                drawCircle(
                    color = if (enabled) {
                        Color(0xFFEDF4FB)
                    } else {
                        Color(0xFFF0F2F5)
                    },
                    radius = baseRadius,
                    center = center
                )
                drawLine(
                    color = Color(0xFFC6D4E3),
                    start = Offset(center.x, center.y - baseRadius),
                    end = Offset(center.x, center.y + baseRadius),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color(0xFFC6D4E3),
                    start = Offset(center.x - baseRadius, center.y),
                    end = Offset(center.x + baseRadius, center.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawCircle(
                    color = Color(0xFF9DB4CE),
                    radius = baseRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.10f),
                    radius = knobRadius + 5.dp.toPx(),
                    center = knobCenter + Offset(0f, 4.dp.toPx())
                )
                drawCircle(
                    color = if (enabled) BlueControl else Color(0xFF9AA5B1),
                    radius = knobRadius,
                    center = knobCenter
                )
            }
        }
        Text(
            "X: " + (vector.x * 100).roundToInt() +
                "    Y: " + (-vector.y * 100).roundToInt(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun applyDeadZone(value: Float): Double {
    val magnitude = abs(value.toDouble())
    if (magnitude < 0.08) return 0.0
    return sign(value.toDouble()) * ((magnitude - 0.08) / 0.92)
}

@Composable
internal fun M3QuickControls(
    enabled: Boolean,
    viewModel: CarViewModel,
    linearSpeed: Double = 0.20,
    angularSpeed: Double = 1.00
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldDriveButton(
                label = "左旋",
                enabled = enabled,
                containerColor = NavyControl,
                onPress = { viewModel.drive(0.0, 0.0, angularSpeed, "左旋") },
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
            HoldDriveButton(
                label = "前进",
                enabled = enabled,
                containerColor = BlueControl,
                onPress = { viewModel.drive(linearSpeed, 0.0, 0.0, "前进") },
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
            HoldDriveButton(
                label = "右旋",
                enabled = enabled,
                containerColor = NavyControl,
                onPress = { viewModel.drive(0.0, 0.0, -angularSpeed, "右旋") },
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldDriveButton(
                label = "左移",
                enabled = enabled,
                containerColor = NavyControl,
                onPress = { viewModel.drive(0.0, linearSpeed, 0.0, "左移") },
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
            HoldDriveButton(
                label = "停止",
                enabled = enabled,
                containerColor = MaterialTheme.colorScheme.error,
                onPress = viewModel::safeStop,
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
            HoldDriveButton(
                label = "右移",
                enabled = enabled,
                containerColor = NavyControl,
                onPress = { viewModel.drive(0.0, -linearSpeed, 0.0, "右移") },
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            HoldDriveButton(
                label = "后退",
                enabled = enabled,
                containerColor = BlueControl,
                onPress = { viewModel.drive(-linearSpeed, 0.0, 0.0, "后退") },
                onRelease = viewModel::safeStop,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HoldDriveButton(
    label: String,
    enabled: Boolean,
    containerColor: Color,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (enabled) containerColor else Color(0xFFE1E6EC),
        contentColor = if (enabled) Color.White else Color(0xFF7A838E),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(62.dp)
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (enabled) onPress()
                    while (awaitPointerEvent().changes.any { it.pressed }) Unit
                    if (enabled) onRelease()
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ControlStatusCard(state: CarUiState) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "控制状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            StatusLine(
                "连接",
                when (state.connectionStatus) {
                    ConnectionStatus.Disconnected -> "未连接"
                    ConnectionStatus.Connecting -> "连接中"
                    ConnectionStatus.Connected -> "已连接"
                }
            )
            StatusLine(
                "遥控",
                if (state.remoteControlArmed) "已启用" else "已锁定"
            )
            StatusLine("命令", state.lastDriveCommand)
            StatusLine("发送帧", state.sentFrameCount.toString())
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
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color(0xFF526579))
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmergencyStopBar(enabled: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(54.dp)
        ) {
            Text(
                "全局紧急停止",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ConnectionBadge(status: ConnectionStatus) {
    val (label, color) = when (status) {
        ConnectionStatus.Disconnected -> "离线" to Color(0xFF687687)
        ConnectionStatus.Connecting -> "连接中" to Color(0xFFB25D00)
        ConnectionStatus.Connected -> "在线" to MaterialTheme.colorScheme.primary
    }
    Surface(
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            fontWeight = FontWeight.SemiBold
        )
    }
}
