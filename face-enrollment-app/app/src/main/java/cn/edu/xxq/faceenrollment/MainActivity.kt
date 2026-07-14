package cn.edu.xxq.faceenrollment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.xxq.faceenrollment.data.MAX_FACE_IMAGES
import cn.edu.xxq.faceenrollment.data.MAX_FACE_TOTAL_BYTES
import cn.edu.xxq.faceenrollment.data.OrderReceipt
import cn.edu.xxq.faceenrollment.data.FaceEndpoint
import cn.edu.xxq.faceenrollment.data.RecognitionSession
import cn.edu.xxq.faceenrollment.data.ServiceStatus
import cn.edu.xxq.faceenrollment.media.FaceImageSample
import cn.edu.xxq.faceenrollment.network.MjpegStreamClient
import cn.edu.xxq.faceenrollment.network.MjpegStreamListener
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val AppBlue = Color(0xFF1769C2)
private val AppBlueDark = Color(0xFF0D4E99)
private val AppGreen = Color(0xFF16835B)
private val AppOrange = Color(0xFFD97706)
private val AppRed = Color(0xFFC62828)
private val AppColors = lightColorScheme(
    primary = AppBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF46617F),
    background = Color(0xFFF4F7FC),
    surface = Color.White,
    error = AppRed,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanupStaleCaptureFiles(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                FaceEnrollmentScreen()
            }
        }
    }
}

@Composable
private fun FaceEnrollmentScreen(viewModel: FaceEnrollmentViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setPollingEnabled(true)
                Lifecycle.Event.ON_STOP -> viewModel.setPollingEnabled(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.setPollingEnabled(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setPollingEnabled(false)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCameraUri?.let(Uri::parse)
        val path = pendingCameraPath
        pendingCameraUri = null
        pendingCameraPath = null
        if (saved && uri != null) {
            viewModel.addImages(listOf(uri), "相机") { deleteCaptureFile(path) }
        } else {
            deleteCaptureFile(path)
            viewModel.reportActionError("拍照已取消")
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_FACE_IMAGES),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris, "相册")
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state, selectedPage)
            PageSelector(selectedPage) { selectedPage = it }
            if (selectedPage == 0) {
                state.notice?.let { MessageBanner(it, isError = false, viewModel::clearMessages) }
                state.actionError?.let { MessageBanner(it, isError = true, viewModel::clearMessages) }
                ServiceCard(state, viewModel)
                StatusCard(state, viewModel)
                VideoRecognitionCard(state, viewModel)
                EnrollmentCard(
                    state = state,
                    viewModel = viewModel,
                    onTakePhoto = {
                        var target: CameraCaptureTarget? = null
                        try {
                            target = createCameraTarget(context)
                            pendingCameraUri = target.uri.toString()
                            pendingCameraPath = target.file.absolutePath
                            cameraLauncher.launch(target.uri)
                        } catch (error: Exception) {
                            deleteCaptureFile(target?.file?.absolutePath)
                            pendingCameraUri = null
                            pendingCameraPath = null
                            viewModel.reportActionError("无法启动系统相机：${error.message ?: "设备不可用"}")
                        }
                    },
                    onPickPhotos = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            } else {
                OrderPage(state, viewModel)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PageSelector(selectedPage: Int, onSelect: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFE0E7F0)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedPage == 0) {
                Button(onClick = { onSelect(0) }, modifier = Modifier.weight(1f)) { Text("人脸采集") }
            } else {
                OutlinedButton(onClick = { onSelect(0) }, modifier = Modifier.weight(1f)) { Text("人脸采集") }
            }
            if (selectedPage == 1) {
                Button(onClick = { onSelect(1) }, modifier = Modifier.weight(1f)) { Text("商品下单") }
            } else {
                OutlinedButton(onClick = { onSelect(1) }, modifier = Modifier.weight(1f)) { Text("商品下单") }
            }
        }
    }
}

@Composable
private fun OrderPage(state: FaceEnrollmentUiState, viewModel: FaceEnrollmentViewModel) {
    val enabledProducts = state.catalog.filter { it.enabled }
    val selectedCount = state.productQuantities.values.sum()
    val identityLinked = state.lastEnrolledPersonId?.isNotBlank() == true &&
        state.orderPersonName.trim() == state.lastEnrolledName?.trim()

    SectionCard("下单服务") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Jetson 订单服务", fontWeight = FontWeight.SemiBold)
                Text(
                    state.serviceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusBadge(
                if (state.serviceStatus != null && state.statusError == null) "在线" else "待连接",
                if (state.serviceStatus != null && state.statusError == null) AppGreen else AppOrange,
            )
        }
        Text(
            "订单会同时绑定最近录入的人脸身份和姓名。此页面只显示本次订单，不会展示人员库或其他用户的订单。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B),
        )
    }

    state.lastOrder?.let { OrderSuccessCard(it) }

    SectionCard("收货信息") {
        OutlinedTextField(
            value = state.orderPersonName,
            onValueChange = viewModel::setOrderPersonName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("姓名") },
            supportingText = {
                Text(
                    when {
                        state.orderPersonName.isBlank() -> "请填写已录入人脸时使用的姓名"
                        identityLinked -> "已关联最近成功录入的人脸"
                        else -> "修改姓名后，需要先用该姓名完成人脸录入"
                    },
                    color = if (identityLinked) AppGreen else Color(0xFF64748B),
                )
            },
            singleLine = true,
            enabled = !state.orderSubmitting,
        )
        OutlinedTextField(
            value = state.roomNumber,
            onValueChange = viewModel::setRoomNumber,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("房间号") },
            placeholder = { Text("例如：A302") },
            supportingText = { Text("请填写小车能够到达的准确房间号") },
            singleLine = true,
            enabled = !state.orderSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
    }

    SectionCard("选择商品") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("可选商品 ${enabledProducts.size} 种", fontWeight = FontWeight.SemiBold)
                state.catalogLoadedAt?.let { timestamp ->
                    val time = remember(timestamp) {
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
                    }
                    Text("列表更新于 $time", style = MaterialTheme.typography.labelSmall, color = Color(0xFF718096))
                }
            }
            OutlinedButton(
                onClick = viewModel::refreshCatalog,
                enabled = !state.catalogLoading && !state.orderSubmitting,
            ) { Text("刷新商品") }
        }

        if (state.catalogLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("正在读取商品列表…", color = Color(0xFF64748B))
        }
        state.catalogError?.let { ErrorText(it) }

        when {
            enabledProducts.isNotEmpty() -> enabledProducts.forEach { product ->
                val quantity = state.productQuantities[product.productId] ?: 0
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (quantity > 0) AppBlue.copy(alpha = 0.08f) else Color(0xFFF7F9FC),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (quantity > 0) AppBlue.copy(alpha = 0.35f) else Color(0xFFE4EAF1),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = quantity > 0,
                            onCheckedChange = { viewModel.toggleProduct(product.productId) },
                            enabled = !state.orderSubmitting,
                        )
                        Text(
                            product.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (quantity > 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = Color(0xFF26384C),
                        )
                        if (quantity > 0) {
                            TextButton(
                                onClick = { viewModel.changeProductQuantity(product.productId, -1) },
                                enabled = !state.orderSubmitting,
                            ) { Text("−", fontSize = 22.sp) }
                            Surface(color = Color.White, shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    quantity.toString(),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.changeProductQuantity(product.productId, 1) },
                                enabled = !state.orderSubmitting && quantity < 99,
                            ) { Text("+", fontSize = 20.sp) }
                        }
                    }
                }
            }
            !state.catalogLoading && state.catalogError == null -> Surface(
                color = Color(0xFFF7F9FC),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "当前没有可下单商品，请稍后刷新。",
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    color = Color(0xFF64748B),
                )
            }
        }

        HorizontalDivider(color = Color(0xFFE3EAF2))
        Text(
            if (selectedCount > 0) "已选择 $selectedCount 件商品" else "尚未选择商品",
            fontWeight = FontWeight.SemiBold,
            color = if (selectedCount > 0) AppBlueDark else Color(0xFF64748B),
        )
        state.orderError?.let { ErrorText(it) }
        Button(
            onClick = viewModel::submitOrder,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.orderSubmitting && enabledProducts.isNotEmpty(),
            contentPadding = PaddingValues(vertical = 13.dp),
        ) {
            if (state.orderSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(9.dp))
                Text("正在提交订单…")
            } else {
                Text("提交订单")
            }
        }
    }
}

@Composable
private fun OrderSuccessCard(order: OrderReceipt) {
    val statusLabel = when (order.status) {
        "pending" -> "待完成"
        "delivering" -> "配送中"
        "completed" -> "已完成"
        "cancelled" -> "已取消"
        else -> order.status
    }
    val statusColor = when (order.status) {
        "completed" -> AppGreen
        "cancelled" -> AppRed
        else -> AppOrange
    }
    SectionCard("下单成功") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("订单已提交", color = AppGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("请等待管理端安排小车配送", color = Color(0xFF64748B))
            }
            StatusBadge(statusLabel, statusColor)
        }
        HorizontalDivider(color = Color(0xFFE3EAF2))
        Text("订单号：${order.orderId}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF53657A))
        Text("收货人：${order.personName}", fontWeight = FontWeight.SemiBold)
        Text("房间号：${order.roomNumber}", fontWeight = FontWeight.SemiBold)
        Text(
            "商品：${order.items.joinToString("、") { "${it.productName} ×${it.quantity}" }}",
            color = Color(0xFF44566C),
        )
    }
}

@Composable
private fun VideoRecognitionCard(state: FaceEnrollmentUiState, viewModel: FaceEnrollmentViewModel) {
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }
    val videoUrl = FaceEndpoint.normalize(state.serviceUrl)?.let { "$it/api/v1/video.mjpg" }
    val session = state.recognitionSession
    SectionCard("实时画面与按需识别") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE7EEF7)),
            contentAlignment = Alignment.Center,
        ) {
            if (videoUrl == null) {
                Text("请先填写有效的人脸服务地址", color = Color(0xFF64748B))
            } else {
                MjpegVideo(videoUrl, reloadKey) { reloadKey += 1 }
            }
        }
        Text(
            "画面来自 Jetson，不会占用本机摄像头。空闲时显示流畅原始画面，识别期间自动显示带框画面。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B),
        )
        if (session?.active == true) {
            val duration = session.durationSeconds.coerceAtLeast(0.1)
            val progress = (session.remainingSeconds / duration).toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "正在识别 · 剩余 ${kotlin.math.ceil(session.remainingSeconds).toInt()} 秒",
                        color = AppOrange,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "已处理 ${session.processedFrames} 帧，请正对小车摄像头",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B),
                    )
                }
                OutlinedButton(
                    onClick = viewModel::cancelRecognition,
                    enabled = !state.recognitionRequestLoading,
                ) {
                    Text(if (state.recognitionRequestLoading) "结束中…" else "提前结束")
                }
            }
        } else {
            Button(
                onClick = viewModel::startRecognition,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.recognitionRequestLoading && videoUrl != null,
            ) {
                if (state.recognitionRequestLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.recognitionRequestLoading) "正在启动…" else "开始识别（5 秒）")
            }
        }
        state.recognitionError?.let { ErrorText(it) }
        if (session?.active != true) RecognitionSessionResult(session)
    }
}

@Composable
private fun RecognitionSessionResult(session: RecognitionSession?) {
    val result = session?.result ?: return
    val (title, detail, color) = when (result.state) {
        "recognized" -> Triple(
            "识别成功",
            buildString {
                append(result.name ?: "已录入人员")
                result.similarity?.let { append(" · 相似度 ${String.format(Locale.US, "%.3f", it)}") }
                if (result.appearances > 0) append(" · 出现 ${result.appearances} 次")
            },
            AppGreen,
        )
        "uncertain" -> Triple(
            "结果不确定",
            buildString {
                append(result.name?.let { "可能是 $it" } ?: "请调整角度后重试")
                result.similarity?.let { append(" · 相似度 ${String.format(Locale.US, "%.3f", it)}") }
            },
            AppOrange,
        )
        "no_face" -> Triple("未检测到人脸", "请正对摄像头并保持光线充足后重试", AppOrange)
        else -> Triple("未识别", "未匹配到已录入人员，请调整距离或光线后重试", Color(0xFF64748B))
    }
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, color = color, fontWeight = FontWeight.Bold)
            Text(detail, color = Color(0xFF44566C), style = MaterialTheme.typography.bodySmall)
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
            contentDescription = "小车摄像头实时画面",
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

@Composable
private fun Header(state: FaceEnrollmentUiState, selectedPage: Int) {
    val (badge, badgeColor) = when {
        state.statusLoading && state.serviceStatus == null -> "连接中" to AppOrange
        state.statusError != null -> "连接异常" to AppRed
        state.serviceStatus != null -> "服务在线" to AppGreen
        else -> "未连接" to Color(0xFF6A7584)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (selectedPage == 0) "用户人脸采集" else "用户商品下单",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF172033),
            )
            Text(
                if (selectedPage == 0) "手机采集 · Jetson 录入 · 小车识别"
                else "填写收货信息 · 选择商品 · 等待配送",
                color = Color(0xFF53657A),
            )
        }
        StatusBadge(badge, badgeColor)
    }
}

@Composable
private fun ServiceCard(state: FaceEnrollmentUiState, viewModel: FaceEnrollmentViewModel) {
    val busy = state.uploading
    SectionCard("服务连接") {
        OutlinedTextField(
            value = state.serviceUrl,
            onValueChange = viewModel::setServiceUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("人脸服务地址") },
            placeholder = { Text("http://10.39.132.165:9095") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(
                onClick = viewModel::refreshStatus,
                enabled = !state.statusLoading && !busy,
            ) { Text("刷新服务") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每 5 秒刷新", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.autoRefresh, onCheckedChange = viewModel::setAutoRefresh)
            }
        }
        Text(
            "服务凭据已内置；App 仅连接同一局域网中的人脸服务，照片不会保存在本 App 的相册中。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B),
        )
    }
}

@Composable
private fun StatusCard(state: FaceEnrollmentUiState, viewModel: FaceEnrollmentViewModel) {
    SectionCard("小车识别状态") {
        if (state.statusLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.statusError?.let { ErrorText(it) }
        val status = state.serviceStatus
        if (status == null) {
            Text(
                "尚未取得服务状态，请确认手机和小车在同一网络。",
                color = Color(0xFF64748B),
            )
        } else {
            StatusSummary(status)
            status.camera.source?.let { Text("摄像头来源：$it", style = MaterialTheme.typography.bodySmall) }
            status.camera.error?.let { ErrorText("摄像头：$it") }
            RecognitionStatusBlock(status)
            HorizontalDivider(color = Color(0xFFE3EAF2))
            LatestRecognitionBlock(status)
            state.statusUpdatedAt?.let { timestamp ->
                val time = remember(timestamp) {
                    DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(timestamp))
                }
                Text("状态更新时间：$time", style = MaterialTheme.typography.labelSmall, color = Color(0xFF718096))
            }
        }
        OutlinedButton(
            onClick = viewModel::refreshStatus,
            enabled = !state.statusLoading,
        ) {
            Text("立即刷新识别结果")
        }
    }
}

@Composable
private fun RecognitionStatusBlock(status: ServiceStatus) {
    val recognition = status.recognition
    val (label, color) = when {
        recognition.error != null -> "推理故障" to AppRed
        !recognition.enabled -> "未启用" to Color(0xFF6A7584)
        recognition.ready -> "推理就绪" to AppGreen
        else -> "尚未就绪" to AppOrange
    }
    Surface(color = color.copy(alpha = 0.09f), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("人脸识别引擎", fontWeight = FontWeight.SemiBold)
                Text(label, color = color, fontWeight = FontWeight.Bold)
            }
            recognition.error?.let { ErrorText("推理服务：$it") }
            recognition.lastSuccessAt?.let {
                Text("最近成功推理：$it", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun StatusSummary(status: ServiceStatus) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusTile(
            modifier = Modifier.weight(1f),
            title = "小车摄像头",
            value = if (status.camera.ready) "已就绪" else "未就绪",
            color = if (status.camera.ready) AppGreen else AppOrange,
        )
        StatusTile(
            modifier = Modifier.weight(1f),
            title = "已录入人员",
            value = status.database.people.toString(),
            color = AppBlue,
        )
        StatusTile(
            modifier = Modifier.weight(1f),
            title = "人脸样本",
            value = status.database.samples.toString(),
            color = AppBlueDark,
        )
    }
}

@Composable
private fun LatestRecognitionBlock(status: ServiceStatus) {
    val latest = status.latest
    Text("最近识别", fontWeight = FontWeight.Bold, color = Color(0xFF25364A))
    when {
        latest == null -> Text("暂无识别记录", color = Color(0xFF64748B))
        latest.faces.isEmpty() -> Text("最近画面未识别到已录入人员", color = Color(0xFF64748B))
        else -> {
            latest.faces.forEach { face ->
                Surface(color = Color(0xFFEAF4FF), shape = RoundedCornerShape(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(face.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "相似度 ${String.format(Locale.US, "%.3f", face.similarity)}",
                            color = AppBlueDark,
                        )
                    }
                }
            }
            latest.timestamp?.let {
                Text("服务时间：$it", style = MaterialTheme.typography.labelSmall, color = Color(0xFF718096))
            }
        }
    }
}

@Composable
private fun EnrollmentCard(
    state: FaceEnrollmentUiState,
    viewModel: FaceEnrollmentViewModel,
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
) {
    val busy = state.processingImages || state.uploading
    SectionCard("录入人员") {
        OutlinedTextField(
            value = state.personName,
            onValueChange = viewModel::setPersonName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("人员姓名") },
            supportingText = { Text("建议使用真实姓名或唯一编号，最多 64 个字符") },
            singleLine = true,
            enabled = !state.uploading,
        )
        Surface(color = Color(0xFFF0F5FB), shape = RoundedCornerShape(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("替换同名人员", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.replaceExisting) "上传成功后覆盖远端同名样本" else "同名人员将按服务端规则追加样本",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B),
                    )
                }
                Switch(
                    checked = state.replaceExisting,
                    onCheckedChange = viewModel::setReplaceExisting,
                    enabled = !state.uploading,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onTakePhoto,
                modifier = Modifier.weight(1f),
                enabled = !busy && state.images.size < MAX_FACE_IMAGES,
            ) { Text("系统相机拍照") }
            OutlinedButton(
                onClick = onPickPhotos,
                modifier = Modifier.weight(1f),
                enabled = !busy && state.images.size < MAX_FACE_IMAGES,
            ) { Text("相册多选") }
        }
        if (state.processingImages) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在纠正方向并压缩图片…")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val totalBytes = state.images.sumOf { it.sizeBytes.toLong() }
            Text(
                "待上传 ${state.images.size}/$MAX_FACE_IMAGES · ${formatBytes(totalBytes)}/20 MiB",
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = viewModel::clearImages, enabled = state.images.isNotEmpty() && !state.uploading) {
                Text("清空")
            }
        }
        if (state.images.isEmpty()) {
            Surface(color = Color(0xFFF7F9FC), shape = RoundedCornerShape(12.dp)) {
                Text(
                    "请采集正脸、轻微左右角度和不同光照下的清晰照片，避免多人同框。",
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    color = Color(0xFF64748B),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.images.forEachIndexed { index, sample ->
                    key(sample.id) {
                        SampleThumbnail(index, sample, !state.uploading) { viewModel.removeImage(sample.id) }
                    }
                }
            }
        }
        Text(
            "每张照片会在手机端纠正 EXIF 方向，缩放至最长边不超过 1600 px，并以 JPEG 质量 85 重编码；单张上限 5 MiB，全部样本合计上限 ${MAX_FACE_TOTAL_BYTES / (1024 * 1024)} MiB。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B),
        )
        Button(
            onClick = viewModel::upload,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && state.images.isNotEmpty() && state.personName.isNotBlank(),
            contentPadding = PaddingValues(vertical = 13.dp),
        ) {
            if (state.uploading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(9.dp))
                Text("正在上传…")
            } else {
                Text(if (state.replaceExisting) "上传并替换人员" else "上传并录入人员")
            }
        }
    }
}

@Composable
private fun SampleThumbnail(
    index: Int,
    sample: FaceImageSample,
    removable: Boolean,
    onRemove: () -> Unit,
) {
    val bitmap = remember(sample.id) {
        BitmapFactory.decodeByteArray(sample.thumbnailJpeg, 0, sample.thumbnailJpeg.size)
    }
    Card(
        modifier = Modifier.width(142.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFD)),
        border = BorderStroke(1.dp, Color(0xFFDCE5EF)),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 张人脸样本",
                    modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp).background(Color(0xFFE5EAF0)),
                    contentAlignment = Alignment.Center,
                ) { Text("预览失败") }
            }
            Text("${index + 1}. ${sample.sourceLabel}", fontWeight = FontWeight.SemiBold)
            Text(
                "${sample.width}×${sample.height} · ${formatBytes(sample.sizeBytes.toLong())}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF64748B),
            )
            TextButton(onClick = onRemove, enabled = removable, modifier = Modifier.fillMaxWidth()) {
                Text("移除", color = if (removable) AppRed else Color.Gray)
            }
        }
    }
}

@Composable
private fun StatusTile(modifier: Modifier, title: String, value: String, color: Color) {
    Surface(modifier = modifier, color = color.copy(alpha = 0.09f), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = Color(0xFF526276), maxLines = 1)
            Text(value, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MessageBanner(message: String, isError: Boolean, onDismiss: () -> Unit) {
    val color = if (isError) AppRed else AppGreen
    Surface(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), color = color)
            TextButton(onClick = onDismiss) { Text("关闭", color = color) }
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = AppRed, style = MaterialTheme.typography.bodySmall)
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
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF1D2A3A))
            content()
        }
    }
}

private data class CameraCaptureTarget(val uri: Uri, val file: File)

private fun createCameraTarget(context: Context): CameraCaptureTarget {
    val directory = File(context.cacheDir, "camera")
    if (!directory.exists() && !directory.mkdirs()) error("无法创建相机缓存目录")
    val file = File.createTempFile("face_capture_", ".jpg", directory)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    return CameraCaptureTarget(uri, file)
}

private fun deleteCaptureFile(path: String?) {
    if (path.isNullOrBlank()) return
    runCatching { File(path).delete() }
}

private fun cleanupStaleCaptureFiles(context: Context) {
    val directory = File(context.cacheDir, "camera")
    val staleBefore = System.currentTimeMillis() - 60 * 60 * 1_000L
    directory.listFiles { file ->
        file.isFile && file.name.startsWith("face_capture_") && file.name.endsWith(".jpg")
    }?.filter { it.lastModified() <= 0L || it.lastModified() < staleBefore }
        ?.forEach { runCatching { it.delete() } }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes / (1024f * 1024f))
    else -> String.format(Locale.US, "%.0f KiB", bytes / 1024f)
}
