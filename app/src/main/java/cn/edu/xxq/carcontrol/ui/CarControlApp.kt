package cn.edu.xxq.carcontrol.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.xxq.carcontrol.CarControlViewModel
import cn.edu.xxq.carcontrol.model.CarUiState
import cn.edu.xxq.carcontrol.model.ConnectionMode
import cn.edu.xxq.carcontrol.model.ControlMode
import cn.edu.xxq.carcontrol.model.DriveDirection
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.foundation.verticalScroll

private val AppColors = lightColorScheme(
    primary = Color(0xFF117A65),
    onPrimary = Color.White,
    secondary = Color(0xFF006C89),
    background = Color(0xFFF5F7F6),
    surface = Color.White,
    onSurface = Color(0xFF17201E),
    error = Color(0xFFBA1A1A)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarControlApp(
    onRemoteScreenChanged: (Boolean) -> Unit,
    viewModel: CarControlViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var showingRemoteControl by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(showingRemoteControl) {
        onRemoteScreenChanged(showingRemoteControl)
    }
    BackHandler(enabled = showingRemoteControl) { showingRemoteControl = false }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme(colorScheme = AppColors) {
        if (showingRemoteControl) {
            RemoteControlScreen(
                state = state,
                viewModel = viewModel,
                onBack = { showingRemoteControl = false }
            )
        } else {
            HomeScreen(
                state = state,
                onOpenRemoteControl = {
                    viewModel.setControlMode(ControlMode.Joystick)
                    showingRemoteControl = true
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(state: CarUiState, onOpenRemoteControl: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("智能小车", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("控制与任务中心", style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("小车连接", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.connected) {
                            "已连接 · ${state.endpoint.mode.label} · ${state.endpoint.host}:${state.endpoint.port}"
                        } else {
                            "未连接 · ${state.endpoint.mode.label} · ${state.endpoint.host}:${state.endpoint.port}"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = onOpenRemoteControl, modifier = Modifier.fillMaxWidth()) {
                        Text("进入遥控器")
                    }
                }
            }
            Text("功能入口", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureEntry("遥控驾驶", "视频、摇杆与紧急停止", "进入", modifier = Modifier.weight(1f)) {
                    onOpenRemoteControl()
                }
                FeatureEntry("SLAM 建图", "地图创建与保存", "后续接入", enabled = false, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureEntry("自动导航", "地图定位与路线任务", "后续接入", enabled = false, modifier = Modifier.weight(1f))
                FeatureEntry("视觉与巡迹", "摄像头、颜色追踪", "后续接入", enabled = false, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeatureEntry(
    title: String,
    description: String,
    action: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}

@Composable
private fun RemoteControlScreen(state: CarUiState, viewModel: CarControlViewModel, onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RemoteMenu(state.connected, onBack, Modifier.width(132.dp).fillMaxHeight())
            VideoPanel(state, Modifier.weight(1f).fillMaxHeight())
            RemoteControlDock(state, viewModel, Modifier.width(320.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun RemoteMenu(connected: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("智能小车", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (connected) "已连接" else "未连接", style = MaterialTheme.typography.labelMedium)
            HorizontalDivider()
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("主页") }
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("建图") }
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("导航") }
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("视觉") }
            Spacer(Modifier.weight(1f))
            Text("遥控器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VideoPanel(state: CarUiState, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF15201D))) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("实时视频", color = Color.White, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (state.showVideo) {
                    VideoPreview(state, Modifier.fillMaxSize())
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("视频画面", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        Text("连接后在右侧开启视频", color = Color(0xFFD7E7E1))
                    }
                }
            }
            Text(
                "${state.endpoint.host}:${state.endpoint.videoPort}/index2",
                color = Color(0xFFD7E7E1),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun RemoteControlDock(state: CarUiState, viewModel: CarControlViewModel, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("遥控器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${state.endpoint.mode.label} · ${state.endpoint.host}:${state.endpoint.port}",
                style = MaterialTheme.typography.labelMedium
            )
            Button(
                onClick = {
                    if (state.connected) viewModel.disconnect() else viewModel.connect()
                },
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.connecting) "连接中" else if (state.connected) "断开连接" else "连接小车") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("视频", modifier = Modifier.weight(1f))
                Switch(checked = state.showVideo, onCheckedChange = viewModel::setVideoVisible)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ControlMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.controlMode == mode,
                        onClick = { viewModel.setControlMode(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Button(
                onClick = viewModel::stop,
                enabled = state.connected,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("紧急停止") }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (state.controlMode) {
                    ControlMode.Buttons -> DirectionPad(
                        enabled = state.connected,
                        onDirection = viewModel::button,
                        onStop = viewModel::stop,
                        modifier = Modifier.fillMaxSize()
                    )
                    ControlMode.Joystick -> Joystick(state.connected, viewModel::joystick, viewModel::stop)
                    ControlMode.Wheels -> WheelPanel(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(connected: Boolean, connecting: Boolean) {
    val color = when {
        connecting -> Color(0xFFE38B00)
        connected -> Color(0xFF168A35)
        else -> Color(0xFF7A8581)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(7.dp))
        Text(if (connecting) "连接中" else if (connected) "在线" else "离线")
    }
}

@Composable
private fun ConnectionPanel(
    state: CarUiState,
    onMode: (ConnectionMode) -> Unit,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onVideoPort: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onVideo: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("连接设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionMode.entries.forEach { mode ->
                    FilterChip(selected = state.endpoint.mode == mode, onClick = { onMode(mode) }, label = { Text(mode.label) })
                }
            }
            OutlinedTextField(
                value = state.endpoint.host,
                onValueChange = onHost,
                label = { Text("小车 IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.endpoint.port,
                onValueChange = onPort,
                label = { Text(if (state.endpoint.mode == ConnectionMode.Tcp) "TCP 端口" else "Bridge 端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.endpoint.videoPort,
                onValueChange = onVideoPort,
                label = { Text("视频端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect, enabled = !state.connecting, modifier = Modifier.weight(1f)) {
                    Text(if (state.connecting) "连接中" else "连接")
                }
                Button(
                    onClick = onDisconnect,
                    enabled = state.connected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("断开") }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("视频预览", fontWeight = FontWeight.Medium)
                    Text("http://${state.endpoint.host}:${state.endpoint.videoPort}/index2", style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = state.showVideo, onCheckedChange = onVideo)
            }
        }
    }
}

@Composable
private fun ControlPanel(state: CarUiState, viewModel: CarControlViewModel, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ControlMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.controlMode == mode,
                        onClick = { viewModel.setControlMode(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::stop,
                enabled = state.connected,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("紧急停止") }
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (state.controlMode) {
                    ControlMode.Buttons -> DirectionPad(
                        enabled = state.connected,
                        onDirection = viewModel::button,
                        onStop = viewModel::stop,
                        modifier = Modifier.fillMaxSize()
                    )
                    ControlMode.Joystick -> Joystick(state.connected, viewModel::joystick, viewModel::stop)
                    ControlMode.Wheels -> WheelPanel(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun DirectionPad(
    enabled: Boolean,
    onDirection: (DriveDirection) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cells = listOf(
        DriveDirection.RotateLeft, DriveDirection.Forward, DriveDirection.RotateRight,
        DriveDirection.StrafeLeft, DriveDirection.Stop, DriveDirection.StrafeRight,
        null, DriveDirection.Reverse, null
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .widthIn(max = 420.dp)
    ) {
        cells.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                row.forEach { direction ->
                    if (direction == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                    else HoldButton(
                        label = direction.label,
                        enabled = enabled,
                        danger = direction == DriveDirection.Stop,
                        onDown = { onDirection(direction) },
                        onRelease = onStop,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldButton(
    label: String,
    enabled: Boolean,
    danger: Boolean,
    onDown: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        danger -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Surface(
        color = color,
        contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.pointerInput(enabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                if (enabled) onDown()
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                }
                if (enabled) onRelease()
            }
        }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Joystick(enabled: Boolean, onVector: (Int, Int) -> Unit, onStop: () -> Unit) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    var lastSentAt by remember { mutableLongStateOf(0L) }
    var xPercent by remember { mutableIntStateOf(0) }
    var yPercent by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight * 0.66f, 250.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(diameter),
                color = Color(0xFF10211E),
                contentColor = Color.White,
                shape = RoundedCornerShape(28.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(enabled) {
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val controlRadius = minOf(size.width, size.height) * 0.46f
                                val limit = controlRadius * 0.62f
                                fun move(position: Offset, force: Boolean = false) {
                                    var delta = position - center
                                    val distance = hypot(delta.x.toDouble(), delta.y.toDouble()).toFloat()
                                    if (distance > limit) delta *= limit / distance
                                    stickOffset = delta
                                    val x = ((delta.x / limit) * 100).roundToInt().coerceIn(-100, 100)
                                    val y = (-(delta.y / limit) * 100).roundToInt().coerceIn(-100, 100)
                                    xPercent = x
                                    yPercent = y
                                    val now = System.currentTimeMillis()
                                    if (enabled && (force || now - lastSentAt >= 50L)) {
                                        lastSentAt = now
                                        onVector(x, y)
                                    }
                                }
                                move(first.position, force = true)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == first.id } ?: break
                                    move(change.position)
                                    if (!change.pressed) break
                                    change.consume()
                                }
                                stickOffset = Offset.Zero
                                xPercent = 0
                                yPercent = 0
                                if (enabled) onStop()
                            }
                        }
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) * 0.46f
                    val travelRadius = radius * 0.62f
                    val knobRadius = radius * 0.22f
                    val knobCenter = center + stickOffset

                    drawCircle(Color(0xFF0A1513), radius, center)
                    drawCircle(Color(0xFF15332D), radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    drawCircle(Color(0xFF203F38), radius * 0.78f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawLine(Color(0xFF31554D), Offset(center.x - travelRadius, center.y), Offset(center.x + travelRadius, center.y), 1.5f)
                    drawLine(Color(0xFF31554D), Offset(center.x, center.y - travelRadius), Offset(center.x, center.y + travelRadius), 1.5f)

                    listOf(
                        Offset(0f, -radius * 0.87f),
                        Offset(radius * 0.87f, 0f),
                        Offset(0f, radius * 0.87f),
                        Offset(-radius * 0.87f, 0f)
                    ).forEach { marker ->
                        drawCircle(Color(0xFF5A7E74), radius * 0.035f, center + marker)
                    }

                    drawCircle(Color.Black.copy(alpha = 0.36f), knobRadius, knobCenter + Offset(0f, radius * 0.06f))
                    drawCircle(if (enabled) Color(0xFF18A783) else Color(0xFF536560), knobRadius, knobCenter)
                    drawCircle(Color(0xFFB6F3E2).copy(alpha = 0.55f), knobRadius * 0.46f, knobCenter - Offset(knobRadius * 0.22f, knobRadius * 0.22f))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) {
                    "X ${xPercent.toString().padStart(3)}  ·  Y ${yPercent.toString().padStart(3)}  ·  松手归中"
                } else {
                    "请先连接小车"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun WheelPanel(state: CarUiState, viewModel: CarControlViewModel) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("课程 TCP 模式支持独立轮速", style = MaterialTheme.typography.labelMedium)
        listOf("左前轮", "左后轮", "右前轮", "右后轮").forEachIndexed { index, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.width(60.dp))
                Slider(
                    value = state.wheelSpeeds[index].toFloat(),
                    onValueChange = { viewModel.updateWheel(index, it) },
                    valueRange = -100f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text("${state.wheelSpeeds[index]}", textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { viewModel.setWheels(state.wheelSpeeds) }, enabled = state.connected) { Text("更新速度") }
            Button(onClick = { (0..3).forEach { viewModel.updateWheel(it, 0f) } }, enabled = state.connected) { Text("全部归零") }
        }
    }
}

@Composable
private fun StatusPanel(state: CarUiState, viewModel: CarControlViewModel, modifier: Modifier = Modifier) {
    val tcpFeaturesAvailable = state.connected && state.endpoint.mode == ConnectionMode.Tcp
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            StatusItem("连接", if (state.connected) "已连接" else "未连接")
            StatusItem("最近指令", state.lastCommand)
            StatusItem("反馈", state.feedback)
            StatusItem("数据帧", state.lastFrame)
            HorizontalDivider()
            Text("扩展控制", style = MaterialTheme.typography.titleSmall)
            Button(onClick = viewModel::photo, enabled = tcpFeaturesAvailable, modifier = Modifier.fillMaxWidth()) { Text("拍照") }
            Button(onClick = viewModel::toggleRecording, enabled = tcpFeaturesAvailable, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.recording) "结束录像" else "开始录像")
            }
            Button(onClick = viewModel::toggleTracking, enabled = tcpFeaturesAvailable, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.trackingEnabled) "停止循迹" else "启动循迹")
            }
            Text("课程 TCP 支持拍照、录像、循迹；Jetson ROS Bridge 只转发底盘运动。", style = MaterialTheme.typography.labelSmall)
            if (state.showVideo) VideoPreview(state)
        }
    }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoPreview(state: CarUiState, modifier: Modifier = Modifier) {
    val url = "http://${state.endpoint.host}:${state.endpoint.videoPort}/index2"
    AndroidView(
        factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true; loadUrl(url) } },
        update = { view -> if (view.url != url) view.loadUrl(url) },
        modifier = modifier
    )
}
