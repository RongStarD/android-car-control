package cn.edu.xxq.rosmastercontrol

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.xxq.rosmastercontrol.model.ControlRules
import cn.edu.xxq.rosmastercontrol.model.DriveCommand
import cn.edu.xxq.rosmastercontrol.network.ConnectionPhase
import cn.edu.xxq.rosmastercontrol.network.DeliveryOrder
import cn.edu.xxq.rosmastercontrol.network.DeliveryOrderStatus
import cn.edu.xxq.rosmastercontrol.network.FacePerson
import cn.edu.xxq.rosmastercontrol.network.FaceRecognitionResult
import cn.edu.xxq.rosmastercontrol.network.MjpegStreamClient
import cn.edu.xxq.rosmastercontrol.network.MjpegStreamListener
import cn.edu.xxq.rosmastercontrol.network.RecognitionResultState
import cn.edu.xxq.rosmastercontrol.network.RecognitionSessionState
import cn.edu.xxq.rosmastercontrol.ui.theme.AppBlue
import cn.edu.xxq.rosmastercontrol.ui.theme.AppBlueDark
import cn.edu.xxq.rosmastercontrol.ui.theme.EmergencyRed
import cn.edu.xxq.rosmastercontrol.ui.theme.RosmasterControlTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RosmasterControlTheme {
                val viewModel: RosmasterControlViewModel = viewModel()
                RosmasterControlScreen(viewModel)
            }
        }
    }
}

@Composable
private fun RosmasterControlScreen(viewModel: RosmasterControlViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.CONTROL) }
    val controlScrollState = rememberScrollState()
    val databaseScrollState = rememberScrollState()
    val ordersScrollState = rememberScrollState()

    LaunchedEffect(state.openControlForOrderId) {
        if (state.openControlForOrderId != null) {
            selectedPage = AppPage.CONTROL
            controlScrollState.scrollTo(0)
            viewModel.consumeOpenControlRequest()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> {
                    viewModel.setForeground(false)
                    viewModel.stopForSafety()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setForeground(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
            viewModel.stopForSafety()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            EmergencyBar(
                enabled = state.connectionPhase == ConnectionPhase.CONNECTED,
                onEmergencyStop = viewModel::emergencyStop,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(
                    when (selectedPage) {
                        AppPage.CONTROL -> controlScrollState
                        AppPage.FACE_DATABASE -> databaseScrollState
                        AppPage.ORDERS -> ordersScrollState
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state.connectionPhase, selectedPage)
            PageSwitcher(
                selectedPage = selectedPage,
                onSelect = { page ->
                    if (selectedPage == AppPage.CONTROL && page != AppPage.CONTROL) {
                        viewModel.stopBeforeLeavingControl()
                    }
                    selectedPage = page
                },
            )
            when (selectedPage) {
                AppPage.CONTROL -> {
                    ConnectionCard(state, viewModel)
                    RobotStatusCard(state)
                    CurrentDeliveryCard(state.currentDeliveryOrder)
                    VideoCard(state, viewModel)
                    ControlCard(state, viewModel)
                }
                AppPage.FACE_DATABASE -> FaceDatabaseCard(state, viewModel)
                AppPage.ORDERS -> OrderManagementCard(state, viewModel)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Header(phase: ConnectionPhase, selectedPage: AppPage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Rosmaster 小车遥控",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF172033),
            )
            Text(
                text = selectedPage.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF53657A),
            )
        }
        ConnectionBadge(phase)
    }
}

@Composable
private fun PageSwitcher(selectedPage: AppPage, onSelect: (AppPage) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppPage.entries.forEach { page ->
            FilterChip(
                selected = selectedPage == page,
                onClick = { onSelect(page) },
                label = { Text(page.label, maxLines = 1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConnectionBadge(phase: ConnectionPhase) {
    val (label, color) = when (phase) {
        ConnectionPhase.CONNECTED -> "已连接" to Color(0xFF16835B)
        ConnectionPhase.CONNECTING -> "连接中" to Color(0xFFE58A00)
        ConnectionPhase.DISCONNECTED -> "未连接" to Color(0xFF6A7584)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConnectionCard(state: ControlUiState, viewModel: RosmasterControlViewModel) {
    val editable = state.connectionPhase == ConnectionPhase.DISCONNECTED
    SectionCard(title = "连接 Jetson") {
        OutlinedTextField(
            value = state.host,
            onValueChange = viewModel::setHost,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Jetson IP / 主机名") },
            singleLine = true,
            enabled = editable,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = state.controlPort,
                onValueChange = viewModel::setControlPort,
                modifier = Modifier.weight(1f),
                label = { Text("控制端口") },
                singleLine = true,
                enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = state.facePort,
                onValueChange = viewModel::setFacePort,
                modifier = Modifier.weight(1f),
                label = { Text("人脸/视频端口") },
                singleLine = true,
                enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        val isDisconnected = state.connectionPhase == ConnectionPhase.DISCONNECTED
        Button(
            onClick = viewModel::connectOrDisconnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.connectionPhase != ConnectionPhase.CONNECTING,
            contentPadding = PaddingValues(vertical = 13.dp),
        ) {
            Text(if (isDisconnected) "连接小车" else "安全停车并断开")
        }
    }
}

@Composable
private fun RobotStatusCard(state: ControlUiState) {
    SectionCard(title = "设备状态") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusTile(
                modifier = Modifier.weight(1f),
                title = "底盘串口",
                ready = state.robot.serialReady,
                readyText = "已就绪",
                waitingText = "未就绪",
            )
            StatusTile(
                modifier = Modifier.weight(1f),
                title = "摄像头",
                ready = state.faceCameraReady,
                readyText = "已就绪",
                waitingText = if (state.faceServiceOnline) "不可用" else "未连接",
            )
            StatusTile(
                modifier = Modifier.weight(1f),
                title = "底盘运动",
                ready = state.robot.moving,
                readyText = "运动中",
                waitingText = "已停车",
                neutralWhenFalse = true,
            )
        }
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF40546B),
        )
        if (state.robot.moving) {
            Text(
                text = "实车反馈：${state.robot.command?.label ?: "运动"} · 速度 ${state.robot.speed} · ${ControlRules.durationLabel(state.robot.durationMs.coerceToSupportedDuration())}",
                style = MaterialTheme.typography.labelLarge,
                color = AppBlueDark,
            )
        }
    }
}

@Composable
private fun StatusTile(
    modifier: Modifier,
    title: String,
    ready: Boolean,
    readyText: String,
    waitingText: String,
    neutralWhenFalse: Boolean = false,
) {
    val color = when {
        ready -> Color(0xFF16835B)
        neutralWhenFalse -> Color(0xFF52677E)
        else -> Color(0xFFD97706)
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.09f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.height(5.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF526276))
            Text(if (ready) readyText else waitingText, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun CurrentDeliveryCard(order: DeliveryOrder?) {
    if (order == null) return
    SectionCard(title = "当前配送") {
        Surface(
            color = Color(0xFFE9F7F1),
            contentColor = Color(0xFF146C4D),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(order.personName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("房间 ${order.roomNumber}", fontWeight = FontWeight.SemiBold)
                }
                Text(
                    formatOrderItems(order),
                    color = Color(0xFF405F55),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "到达后请在实时画面下点击“识别收货人”。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun OrderManagementCard(state: ControlUiState, viewModel: RosmasterControlViewModel) {
    var selectedStatus by rememberSaveable { mutableStateOf(DeliveryOrderStatus.PENDING) }
    var pendingCancel by remember { mutableStateOf<DeliveryOrder?>(null) }

    pendingCancel?.let { order ->
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text("取消订单") },
            text = {
                Text("确定取消 ${order.personName} 的订单吗？房间 ${order.roomNumber}，取消后不能继续配送。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCancel = null
                        viewModel.cancelOrder(order.orderId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                ) { Text("确认取消") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) { Text("返回") }
            },
        )
    }

    SectionCard(title = "订单管理") {
        state.currentDeliveryOrder?.let { current ->
            Surface(
                color = Color(0xFFE9F7F1),
                contentColor = Color(0xFF146C4D),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "正在配送：${current.personName} · 房间 ${current.roomNumber}",
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("配送任务", fontWeight = FontWeight.Bold, color = Color(0xFF25364A))
                state.ordersUpdatedAt?.let { timestamp ->
                    val formatted = remember(timestamp) {
                        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))
                    }
                    Text(
                        "更新于 $formatted",
                        color = Color(0xFF6B7788),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            OutlinedButton(
                onClick = { viewModel.refreshOrders() },
                enabled = !state.ordersLoading && state.orderActionId == null,
            ) {
                Text("刷新")
            }
        }

        if (state.ordersLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        OrderStatusFilterRows(
            selected = selectedStatus,
            counts = DeliveryOrderStatus.entries.associateWith { status ->
                state.orders.count { it.status == status }
            },
            onSelect = { selectedStatus = it },
        )

        state.ordersError?.let { error ->
            Text(error, color = EmergencyRed, style = MaterialTheme.typography.bodySmall)
            Text(
                "请确认 Jetson 的 9095 服务已更新并手动启动。",
                color = Color(0xFF6B7788),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        val visibleOrders = state.orders.filter { it.status == selectedStatus }
        if (!state.ordersLoading && visibleOrders.isEmpty()) {
            Text(
                "暂无${selectedStatus.label}订单",
                color = Color(0xFF64748B),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            visibleOrders.forEach { order ->
                DeliveryOrderCard(
                    order = order,
                    currentOrderId = state.currentDeliveryOrder?.orderId,
                    actionId = state.orderActionId,
                    enabled = !state.ordersLoading && state.orderActionId == null,
                    onStart = { viewModel.startDelivery(order.orderId) },
                    onCancel = { pendingCancel = order },
                )
            }
        }
    }
}

@Composable
private fun OrderStatusFilterRows(
    selected: DeliveryOrderStatus,
    counts: Map<DeliveryOrderStatus, Int>,
    onSelect: (DeliveryOrderStatus) -> Unit,
) {
    DeliveryOrderStatus.entries.chunked(2).forEach { rowStatuses ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowStatuses.forEach { status ->
                FilterChip(
                    selected = selected == status,
                    onClick = { onSelect(status) },
                    label = { Text("${status.label} ${counts[status] ?: 0}") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DeliveryOrderCard(
    order: DeliveryOrder,
    currentOrderId: String?,
    actionId: String?,
    enabled: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val statusColor = orderStatusColor(order.status)
    Surface(color = Color(0xFFF5F8FC), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(order.personName, fontWeight = FontWeight.Bold, color = Color(0xFF25364A))
                    Text("房间 ${order.roomNumber}", color = Color(0xFF52677E))
                }
                Surface(
                    color = statusColor.copy(alpha = 0.11f),
                    contentColor = statusColor,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        order.status.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(formatOrderItems(order), color = Color(0xFF40546B))
            Text(
                "下单 ${formatOrderTime(order.createdAt)} · ${shortOrderId(order.orderId)}",
                color = Color(0xFF7A8796),
                style = MaterialTheme.typography.labelSmall,
            )

            if (order.status == DeliveryOrderStatus.PENDING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStart,
                        enabled = enabled && currentOrderId == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (actionId == order.orderId) "处理中…" else "开始配送")
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("取消订单") }
                }
                if (currentOrderId != null) {
                    Text(
                        "请先完成或取消当前配送订单。",
                        color = Color(0xFFD97706),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (order.status == DeliveryOrderStatus.DELIVERING) {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (actionId == order.orderId) "处理中…" else "取消配送")
                }
            }
        }
    }
}

private fun orderStatusColor(status: DeliveryOrderStatus): Color = when (status) {
    DeliveryOrderStatus.PENDING -> Color(0xFFD97706)
    DeliveryOrderStatus.DELIVERING -> Color(0xFF1976D2)
    DeliveryOrderStatus.COMPLETED -> Color(0xFF16835B)
    DeliveryOrderStatus.CANCELLED -> Color(0xFF6A7584)
}

private fun formatOrderItems(order: DeliveryOrder): String =
    order.items.joinToString(" · ") { "${it.productName} ×${it.quantity}" }
        .ifBlank { "未记录商品" }

private fun shortOrderId(orderId: String): String =
    "订单 ${orderId.take(8)}${if (orderId.length > 8) "…" else ""}"

@Composable
private fun VideoCard(state: ControlUiState, viewModel: RosmasterControlViewModel) {
    var reloadKey by remember(state.videoUrl) { mutableIntStateOf(0) }
    val videoEnabled = state.faceServiceOnline && state.faceCameraReady

    SectionCard(title = "实时画面") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE8EEF6)),
            contentAlignment = Alignment.Center,
        ) {
            if (videoEnabled) {
                MjpegVideo(
                    url = state.videoUrl,
                    reloadKey = reloadKey,
                    onRetry = { reloadKey += 1 },
                )
            } else {
                VideoPlaceholder(state)
            }
        }
        Text(
            text = "MJPEG：${state.videoUrl}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF6B7788),
        )
        RecognitionControls(state, viewModel)
    }
}

@Composable
private fun RecognitionControls(state: ControlUiState, viewModel: RosmasterControlViewModel) {
    val session = state.recognitionSession
    val currentOrder = state.currentDeliveryOrder
    if (session.active) {
        Surface(
            color = Color(0xFFFFF4E5),
            contentColor = Color(0xFF8A4B00),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (session.orderId != null) "正在验证收货人" else "正在识别人脸",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "剩余 ${formatSeconds(session.remainingSeconds)} 秒 · 已处理 ${session.processedFrames} 帧",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelRecognition,
                        enabled = !state.recognitionSessionBusy,
                    ) {
                        Text(if (state.recognitionSessionBusy) "正在结束…" else "提前结束")
                    }
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "识别期间画面会显示人脸框，结束后自动恢复流畅原画面。",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    } else {
        state.orderVerificationResult?.let { verification ->
            val color = when {
                verification.completed -> Color(0xFF16835B)
                verification.matched -> Color(0xFFD97706)
                else -> EmergencyRed
            }
            val title = when {
                verification.completed -> "收货验证成功，订单已完成"
                verification.matched -> "人员匹配，但订单未完成"
                else -> "收货人不匹配，订单保持配送中"
            }
            Surface(
                color = color.copy(alpha = 0.10f),
                contentColor = color,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(
                        orderVerificationMessage(verification, session.result),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        recognitionSummary(session.state, session.result)?.let { summary ->
            Surface(
                color = summary.color.copy(alpha = 0.10f),
                contentColor = summary.color,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(summary.title, fontWeight = FontWeight.Bold)
                    summary.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Button(
            onClick = viewModel::startRecognition,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.faceServiceOnline &&
                state.faceCameraReady &&
                state.faceRecognitionReady &&
                state.orderStateReliable &&
                !state.recognitionSessionBusy,
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            Text(
                when {
                    state.recognitionSessionBusy -> "正在启动识别…"
                    currentOrder != null -> "识别收货人（5 秒）"
                    else -> "开始识别（5 秒）"
                },
            )
        }
        when {
            !state.orderStateReliable -> Text(
                "订单状态未知，请先刷新订单",
                color = Color(0xFFD97706),
                style = MaterialTheme.typography.labelSmall,
            )
            currentOrder != null -> Text(
                "将核验订单：${currentOrder.personName} · 房间 ${currentOrder.roomNumber}",
                color = Color(0xFF52677E),
                style = MaterialTheme.typography.labelSmall,
            )
            else -> Text(
                "当前没有配送中订单，本次仅进行通用人脸识别。",
                color = Color(0xFF6B7788),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (state.faceServiceOnline && state.faceCameraReady && !state.faceRecognitionReady) {
            Text(
                "识别引擎尚未就绪，请稍候再试。",
                color = Color(0xFFD97706),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    state.recognitionSessionError?.let { message ->
        Text(message, color = EmergencyRed, style = MaterialTheme.typography.bodySmall)
    }
}

private data class RecognitionSummary(
    val title: String,
    val detail: String? = null,
    val color: Color,
)

private fun orderVerificationMessage(
    verification: cn.edu.xxq.rosmastercontrol.network.OrderVerificationResult,
    recognition: FaceRecognitionResult?,
): String = when {
    verification.completed -> "收货人与订单绑定用户一致，系统已自动标记为已完成。"
    recognition?.state == RecognitionResultState.NO_FACE ->
        "本次没有检测到人脸，订单仍为配送中，请调整位置后重试。"
    recognition?.state == RecognitionResultState.UNKNOWN ->
        "检测到的人脸不在人员库中，订单仍为配送中。"
    recognition?.state == RecognitionResultState.UNCERTAIN ->
        "人脸相似度不足，暂时无法确认收货人，订单仍为配送中。"
    !verification.matched ->
        "识别人员与订单绑定用户不一致，订单仍为配送中。"
    verification.matched ->
        "人员已匹配，但订单状态尚未完成，请刷新订单后重试。"
    verification.message.any { it in '\u4E00'..'\u9FFF' } -> verification.message
    else -> "订单状态没有改变，请刷新后查看。"
}

private fun recognitionSummary(
    sessionState: RecognitionSessionState,
    result: FaceRecognitionResult?,
): RecognitionSummary? {
    if (result != null) {
        val detailParts = buildList {
            result.similarity?.let {
                add("相似度 ${String.format(Locale.getDefault(), "%.1f%%", it * 100.0)}")
            }
            if (result.appearances > 0) add("出现 ${result.appearances} 次")
        }
        val detail = detailParts.joinToString(" · ").ifBlank { null }
        return when (result.state) {
            RecognitionResultState.RECOGNIZED -> RecognitionSummary(
                title = "识别成功：${result.name ?: "已录人员"}",
                detail = detail,
                color = Color(0xFF16835B),
            )
            RecognitionResultState.UNCERTAIN -> RecognitionSummary(
                title = result.name?.let { "可能是：$it" } ?: "识别结果不确定",
                detail = detail,
                color = Color(0xFFD97706),
            )
            RecognitionResultState.UNKNOWN -> RecognitionSummary(
                title = "未匹配到已录人员",
                detail = detail,
                color = Color(0xFF52677E),
            )
            RecognitionResultState.NO_FACE -> RecognitionSummary(
                title = "未检测到人脸",
                detail = "请正对摄像头后重新识别",
                color = Color(0xFFD97706),
            )
        }
    }
    return when (sessionState) {
        RecognitionSessionState.IDLE,
        RecognitionSessionState.RUNNING,
        -> null
        RecognitionSessionState.COMPLETED -> RecognitionSummary(
            title = "识别已结束",
            detail = "服务未返回可用结果",
            color = Color(0xFF52677E),
        )
        RecognitionSessionState.CANCELLED -> RecognitionSummary(
            title = "识别已提前结束",
            color = Color(0xFF52677E),
        )
        RecognitionSessionState.FAILED -> RecognitionSummary(
            title = "识别失败",
            detail = "请检查摄像头和识别服务后重试",
            color = EmergencyRed,
        )
    }
}

private fun formatSeconds(seconds: Double): String =
    String.format(Locale.getDefault(), "%.1f", seconds.coerceAtLeast(0.0))

@Composable
private fun VideoPlaceholder(
    state: ControlUiState,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when {
                !state.faceServiceOnline -> "9095 人脸服务未启动，请在 Jetson 手动启动"
                !state.faceCameraReady -> "人脸服务已启动，正在等待摄像头源"
                !state.faceRecognitionReady -> "摄像头已连接，识别引擎正在准备"
                else -> "正在加载视频…"
            },
            color = Color(0xFF586A7E),
        )
    }
}

@Composable
private fun FaceDatabaseCard(state: ControlUiState, viewModel: RosmasterControlViewModel) {
    var pendingDelete by remember { mutableStateOf<FacePerson?>(null) }
    pendingDelete?.let { person ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除人脸人员") },
            text = { Text("确定删除“${person.name}”及其 ${person.samples} 个人脸样本吗？此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        viewModel.deleteFacePerson(person.name)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    SectionCard(title = "人脸数据库") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FaceStatTile(
                modifier = Modifier.weight(1f),
                label = "已录人员",
                value = state.facePeopleCount.toString(),
            )
            FaceStatTile(
                modifier = Modifier.weight(1f),
                label = "人脸样本",
                value = state.faceSampleCount.toString(),
            )
            FaceStatTile(
                modifier = Modifier.weight(1f),
                label = "数据库版本",
                value = state.faceDatabaseRevision.toString(),
            )
        }

        if (state.faceServiceLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val (statusLabel, statusColor) = when {
            state.faceRecognitionReady -> "识别就绪" to Color(0xFF16835B)
            state.faceCameraReady -> "识别准备中" to Color(0xFFD97706)
            state.faceServiceOnline -> "等待摄像头" to Color(0xFFD97706)
            else -> "9095 未启动" to Color(0xFF6A7584)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                color = statusColor.copy(alpha = 0.11f),
                contentColor = statusColor,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    statusLabel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = { viewModel.refreshFaceService() },
                enabled = !state.faceServiceLoading && state.deletingFaceName == null,
            ) {
                Text("刷新")
            }
        }

        state.faceError?.let { message ->
            Text(message, color = EmergencyRed, style = MaterialTheme.typography.bodySmall)
            if (!state.faceServiceOnline) {
                Text(
                    "请在 Jetson 执行：sudo face-control start",
                    color = Color(0xFF6B7788),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        state.faceUpdatedAt?.let { timestamp ->
            val formatted = remember(timestamp) {
                DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))
            }
            Text(
                "数据更新时间：$formatted",
                color = Color(0xFF6B7788),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        HorizontalDivider(color = Color(0xFFE3EAF2))
        Text("人员管理", fontWeight = FontWeight.Bold, color = Color(0xFF25364A))

        when {
            !state.faceServiceOnline && state.facePeople.isEmpty() -> Text(
                "手动启动 9095 后即可读取人员库。",
                color = Color(0xFF64748B),
            )
            state.facePeople.isEmpty() -> Text("远端人员库为空", color = Color(0xFF64748B))
            else -> state.facePeople.forEach { person ->
                FacePersonRow(
                    person = person,
                    deleting = state.deletingFaceName == person.name,
                    enabled = state.deletingFaceName == null && !state.faceServiceLoading,
                    onDelete = { pendingDelete = person },
                )
            }
        }

        Text(
            "新增或替换人脸样本请继续使用人脸采集 App。",
            color = Color(0xFF6B7788),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun FaceStatTile(modifier: Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        color = Color(0xFFEAF2FC),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF526276))
            Text(value, fontWeight = FontWeight.Bold, color = AppBlueDark, fontSize = 19.sp)
        }
    }
}

@Composable
private fun FacePersonRow(
    person: FacePerson,
    deleting: Boolean,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Surface(color = Color(0xFFF5F8FC), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(person.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF25364A))
                Text(
                    "${person.samples} 个样本",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF64748B),
                )
            }
            TextButton(onClick = onDelete, enabled = enabled) {
                Text(if (deleting) "删除中…" else "删除", color = if (enabled) EmergencyRed else Color.Gray)
            }
        }
    }
}

@Composable
private fun MjpegVideo(url: String, reloadKey: Int, onRetry: () -> Unit) {
    var frame by remember(url, reloadKey) { mutableStateOf<Bitmap?>(null) }
    var phase by remember(url, reloadKey) { mutableStateOf(VideoPhase.CONNECTING) }
    var lastError by remember(url, reloadKey) { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleActive by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> lifecycleActive = true
                Lifecycle.Event.ON_STOP -> lifecycleActive = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (lifecycleActive) {
        DisposableEffect(url, reloadKey) {
            val client = MjpegStreamClient(
                listener = object : MjpegStreamListener {
                    override fun onConnecting() {
                        phase = VideoPhase.CONNECTING
                    }

                    override fun onFrame(bitmap: Bitmap) {
                        frame = bitmap
                        lastError = null
                        phase = VideoPhase.STREAMING
                    }

                    override fun onError(message: String) {
                        lastError = message
                        phase = VideoPhase.ERROR
                    }
                },
            )
            client.start(url)
            onDispose { client.close() }
        }
    }

    frame?.let { bitmap ->
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "摄像头实时画面",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }

    if (frame == null || phase != VideoPhase.STREAMING) {
        Surface(
            color = if (frame == null) Color.Transparent else Color(0xB3000000),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when {
                        !lifecycleActive -> "视频已暂停"
                        phase == VideoPhase.ERROR -> "视频连接失败"
                        lastError != null -> "正在重新连接视频…"
                        else -> "正在加载视频…"
                    },
                    color = if (frame == null) Color(0xFF586A7E) else Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
                lastError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (frame == null) Color(0xFF7A4C00) else Color(0xFFFFD7A3),
                    )
                    TextButton(onClick = onRetry) { Text("重新加载") }
                }
            }
        }
    }
}

private enum class VideoPhase { CONNECTING, STREAMING, ERROR }

private enum class AppPage(val label: String, val subtitle: String) {
    CONTROL("控制画面", "底盘控制与实时人脸识别画面"),
    ORDERS("订单管理", "配送任务、房间与完成状态"),
    FACE_DATABASE("人脸库", "人脸统计与人员管理"),
}

@Composable
private fun ControlCard(state: ControlUiState, viewModel: RosmasterControlViewModel) {
    val driveEnabled = state.canControl && !state.robot.followLine
    SectionCard(title = "遥控面板") {
        Text("速度", style = MaterialTheme.typography.labelLarge, color = Color(0xFF47596D))
        ChoiceRow(
            values = ControlRules.speeds,
            selected = state.selectedSpeed,
            label = { it.toString() },
            enabled = state.activeCommand == null,
            onSelect = viewModel::selectSpeed,
        )
        Text("持续时间", style = MaterialTheme.typography.labelLarge, color = Color(0xFF47596D))
        ChoiceRow(
            values = ControlRules.durationsMs,
            selected = state.selectedDurationMs,
            label = ControlRules::durationLabel,
            enabled = state.activeCommand == null,
            onSelect = viewModel::selectDuration,
        )

        Text(
            text = if (state.selectedDurationMs == 0) "持续模式：按住移动，松手立即停车" else "定时模式：触发后自动停车",
            style = MaterialTheme.typography.bodySmall,
            color = AppBlueDark,
        )

        DirectionGrid(
            enabled = driveEnabled,
            durationMs = state.selectedDurationMs,
            activeCommand = state.activeCommand,
            onPress = viewModel::pressDrive,
            onRelease = viewModel::releaseDrive,
            onStop = viewModel::stop,
        )

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("寻迹", fontWeight = FontWeight.Bold)
                    Text(
                        "启用后暂停手动方向控制",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5B6878),
                    )
                }
                Switch(
                    checked = state.robot.followLine,
                    onCheckedChange = viewModel::setFollowLine,
                    enabled = state.canControl && state.activeCommand == null,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun DirectionGrid(
    enabled: Boolean,
    durationMs: Int,
    activeCommand: DriveCommand?,
    onPress: (DriveCommand) -> Unit,
    onRelease: (DriveCommand) -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DirectionRow(
            left = DriveCommand.TURN_LEFT,
            center = DriveCommand.FORWARD,
            right = DriveCommand.TURN_RIGHT,
            enabled = enabled,
            durationMs = durationMs,
            activeCommand = activeCommand,
            onPress = onPress,
            onRelease = onRelease,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            MotionButton(
                command = DriveCommand.STRAFE_LEFT,
                enabled = enabled,
                active = activeCommand == DriveCommand.STRAFE_LEFT,
                durationMs = durationMs,
                onPress = onPress,
                onRelease = onRelease,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f).height(62.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EmergencyRed),
                shape = RoundedCornerShape(13.dp),
            ) {
                Text("停止", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
            MotionButton(
                command = DriveCommand.STRAFE_RIGHT,
                enabled = enabled,
                active = activeCommand == DriveCommand.STRAFE_RIGHT,
                durationMs = durationMs,
                onPress = onPress,
                onRelease = onRelease,
                modifier = Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(9.dp))
            MotionButton(
                command = DriveCommand.BACKWARD,
                enabled = enabled,
                active = activeCommand == DriveCommand.BACKWARD,
                durationMs = durationMs,
                onPress = onPress,
                onRelease = onRelease,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(9.dp))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DirectionRow(
    left: DriveCommand,
    center: DriveCommand,
    right: DriveCommand,
    enabled: Boolean,
    durationMs: Int,
    activeCommand: DriveCommand?,
    onPress: (DriveCommand) -> Unit,
    onRelease: (DriveCommand) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        listOf(left, center, right).forEach { command ->
            MotionButton(
                command = command,
                enabled = enabled,
                active = activeCommand == command,
                durationMs = durationMs,
                onPress = onPress,
                onRelease = onRelease,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MotionButton(
    command: DriveCommand,
    enabled: Boolean,
    active: Boolean,
    durationMs: Int,
    onPress: (DriveCommand) -> Unit,
    onRelease: (DriveCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        !enabled -> Color(0xFFDDE4EC)
        active -> AppBlueDark
        else -> AppBlue
    }
    val foreground = if (enabled) Color.White else Color(0xFF8A96A3)
    Box(
        modifier = modifier
            .height(62.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(background)
            .semantics {
                role = Role.Button
                contentDescription = command.label
            }
            .focusable(enabled)
            .pointerInput(enabled, durationMs, command) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPress(command)
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        if (durationMs == 0) onRelease(command)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(command.label, color = foreground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun EmergencyBar(enabled: Boolean, onEmergencyStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = onEmergencyStop,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmergencyRed,
                disabledContainerColor = Color(0xFFE1A2A2),
            ),
            contentPadding = PaddingValues(vertical = 15.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("全局紧急停止", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE0E7F0)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D2A3A),
            )
            content()
        }
    }
}

private fun Int.coerceToSupportedDuration(): Int = when {
    this <= 0 -> 0
    this <= 500 -> 500
    else -> 1_000
}
