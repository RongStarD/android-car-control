package cn.edu.xxq.carcontrol.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.consume
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
fun CarControlApp(viewModel: CarControlViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme(colorScheme = AppColors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("智能小车控制", fontWeight = FontWeight.Bold)
                            Text(
                                if (state.connected) "已连接 ${state.endpoint.host}:${state.endpoint.port}" else state.feedback,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    },
                    actions = {
                        ConnectionIndicator(connected = state.connected, connecting = state.connecting)
                        Spacer(Modifier.width(16.dp))
                    }
                )
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ConnectionPanel(
                    state = state,
                    onMode = viewModel::updateMode,
                    onHost = viewModel::updateHost,
                    onPort = viewModel::updatePort,
                    onVideoPort = viewModel::updateVideoPort,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onVideo = viewModel::setVideoVisible,
                    modifier = Modifier.widthIn(min = 270.dp, max = 330.dp).fillMaxHeight()
                )
                ControlPanel(state, viewModel, Modifier.weight(1f).fillMaxHeight())
                StatusPanel(state, viewModel, Modifier.widthIn(min = 210.dp, max = 270.dp).fillMaxHeight())
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
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlMode.entries.forEach { mode ->
                    FilterChip(selected = state.controlMode == mode, onClick = { viewModel.setControlMode(mode) }, label = { Text(mode.label) })
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = viewModel::stop,
                    enabled = state.connected,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("紧急停止") }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state.controlMode) {
                    ControlMode.Buttons -> DirectionPad(state.connected, viewModel::button, viewModel::stop)
                    ControlMode.Joystick -> Joystick(state.connected, viewModel::joystick, viewModel::stop)
                    ControlMode.Wheels -> WheelPanel(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun DirectionPad(enabled: Boolean, onDirection: (DriveDirection) -> Unit, onStop: () -> Unit) {
    val cells = listOf(
        DriveDirection.RotateLeft, DriveDirection.Forward, DriveDirection.RotateRight,
        DriveDirection.StrafeLeft, DriveDirection.Stop, DriveDirection.StrafeRight,
        null, DriveDirection.Reverse, null
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().widthIn(max = 330.dp)
    ) {
        cells.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { direction ->
                    if (direction == null) Spacer(Modifier.weight(1f).aspectRatio(1.4f))
                    else HoldButton(
                        label = direction.label,
                        enabled = enabled,
                        danger = direction == DriveDirection.Stop,
                        onDown = { onDirection(direction) },
                        onRelease = onStop,
                        modifier = Modifier.weight(1f).aspectRatio(1.4f)
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
    var lastSentAt by remember { mutableStateOf(0L) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 280.dp)
                .aspectRatio(1f)
                .clipToBounds()
                .pointerInput(enabled) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val limit = size.minDimension / 2f - 46f
                        fun move(position: Offset, force: Boolean = false) {
                            var delta = position - center
                            val distance = hypot(delta.x.toDouble(), delta.y.toDouble()).toFloat()
                            if (distance > limit) delta *= limit / distance
                            stickOffset = delta
                            val x = ((delta.x / limit) * 100).roundToInt().coerceIn(-100, 100)
                            val y = (-(delta.y / limit) * 100).roundToInt().coerceIn(-100, 100)
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
                        if (enabled) onStop()
                    }
                }
        ) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFFD7E7E1), radius, center)
            drawCircle(Color(0xFF9ABAB0), radius * 0.72f, center)
            drawCircle(Color(0xFF117A65), radius * 0.30f, center + stickOffset)
        }
        Spacer(Modifier.height(12.dp))
        Text("松手自动归中并停车", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun WheelPanel(state: CarUiState, viewModel: CarControlViewModel) {
    Column(modifier = Modifier.widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun VideoPreview(state: CarUiState) {
    val url = "http://${state.endpoint.host}:${state.endpoint.videoPort}/index2"
    AndroidView(
        factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true; loadUrl(url) } },
        update = { view -> if (view.url != url) view.loadUrl(url) },
        modifier = Modifier.fillMaxWidth().weight(1f)
    )
}
