package cn.edu.xxq.rosmastercontrol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.xxq.rosmastercontrol.model.CONTROL_PROTOCOL_VERSION
import cn.edu.xxq.rosmastercontrol.model.ControlProtocol
import cn.edu.xxq.rosmastercontrol.model.ControlRules
import cn.edu.xxq.rosmastercontrol.model.DriveCommand
import cn.edu.xxq.rosmastercontrol.model.RobotState
import cn.edu.xxq.rosmastercontrol.model.ServerMessage
import cn.edu.xxq.rosmastercontrol.network.ConnectionPhase
import cn.edu.xxq.rosmastercontrol.network.ControlConnectionListener
import cn.edu.xxq.rosmastercontrol.network.DeliveryOrder
import cn.edu.xxq.rosmastercontrol.network.DeliveryOrderStatus
import cn.edu.xxq.rosmastercontrol.network.FaceApiResult
import cn.edu.xxq.rosmastercontrol.network.FacePerson
import cn.edu.xxq.rosmastercontrol.network.OrderVerificationResult
import cn.edu.xxq.rosmastercontrol.network.RecognitionSession
import cn.edu.xxq.rosmastercontrol.network.FaceServiceClient
import cn.edu.xxq.rosmastercontrol.network.WebSocketControlClient
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ControlUiState(
    val host: String = "10.39.132.165",
    val controlPort: String = "9093",
    val facePort: String = "9095",
    val connectionPhase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val protocolAccepted: Boolean = false,
    val robot: RobotState = RobotState(),
    val selectedSpeed: Int = 50,
    val selectedDurationMs: Int = 0,
    val activeCommand: DriveCommand? = null,
    val message: String = "请连接 Jetson 控制桥",
    val faceServiceOnline: Boolean = false,
    val faceServiceLoading: Boolean = false,
    val faceCameraReady: Boolean = false,
    val faceRecognitionReady: Boolean = false,
    val facePeopleCount: Int = 0,
    val faceSampleCount: Int = 0,
    val faceDatabaseRevision: Long = 0,
    val facePeople: List<FacePerson> = emptyList(),
    val faceError: String? = null,
    val deletingFaceName: String? = null,
    val faceUpdatedAt: Long? = null,
    val recognitionSession: RecognitionSession = RecognitionSession(),
    val recognitionSessionBusy: Boolean = false,
    val recognitionSessionError: String? = null,
    val orders: List<DeliveryOrder> = emptyList(),
    val ordersLoading: Boolean = false,
    val ordersError: String? = null,
    val orderActionId: String? = null,
    val ordersUpdatedAt: Long? = null,
    val orderVerificationResult: OrderVerificationResult? = null,
    val orderVerificationOrderId: String? = null,
    val openControlForOrderId: String? = null,
) {
    val canControl: Boolean
        get() = connectionPhase == ConnectionPhase.CONNECTED &&
            protocolAccepted &&
            robot.serialReady

    val faceBaseUrl: String
        get() = "http://${host.trim()}:${facePort}"

    val videoUrl: String
        get() = "$faceBaseUrl/api/v1/video.mjpg"

    val currentDeliveryOrder: DeliveryOrder?
        get() = orders.firstOrNull { it.status == DeliveryOrderStatus.DELIVERING }

    val orderStateReliable: Boolean
        get() = ordersUpdatedAt != null &&
            ordersError == null &&
            !ordersLoading &&
            orderActionId == null
}

class RosmasterControlViewModel : ViewModel(), ControlConnectionListener {
    private val _uiState = MutableStateFlow(ControlUiState())
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    private val requestIds = AtomicLong(1)
    private val client = WebSocketControlClient(this)
    private val faceClient = FaceServiceClient()
    private var activeDriveId: Long? = null
    private var heartbeatJob: Job? = null
    private var timedStopJob: Job? = null
    private var followLineId: Long? = null
    private var followHeartbeatJob: Job? = null
    private var faceRequestJob: Job? = null
    private var faceDeleteJob: Job? = null
    private var facePollingJob: Job? = null
    private var recognitionSessionActionJob: Job? = null
    private var recognitionSessionPollingJob: Job? = null
    private var ordersRequestJob: Job? = null
    private var ordersPollingJob: Job? = null
    private var orderActionJob: Job? = null
    private var handledOrderRecognitionSessionId: String? = null
    private var followConfirmed = false
    private var foreground = false

    fun setHost(value: String) {
        if (_uiState.value.connectionPhase == ConnectionPhase.DISCONNECTED) {
            _uiState.update {
                it.copy(
                    host = value.take(253),
                    faceServiceOnline = false,
                    faceCameraReady = false,
                    faceRecognitionReady = false,
                    recognitionSession = RecognitionSession(),
                    recognitionSessionBusy = false,
                    recognitionSessionError = null,
                    orders = emptyList(),
                    ordersLoading = false,
                    ordersError = null,
                    ordersUpdatedAt = null,
                    orderActionId = null,
                    orderVerificationResult = null,
                    orderVerificationOrderId = null,
                )
            }
        }
    }

    fun setControlPort(value: String) {
        if (_uiState.value.connectionPhase == ConnectionPhase.DISCONNECTED) {
            _uiState.update { it.copy(controlPort = value.filter(Char::isDigit).take(5)) }
        }
    }

    fun setFacePort(value: String) {
        if (_uiState.value.connectionPhase == ConnectionPhase.DISCONNECTED) {
            _uiState.update {
                it.copy(
                    facePort = value.filter(Char::isDigit).take(5),
                    faceServiceOnline = false,
                    faceCameraReady = false,
                    faceRecognitionReady = false,
                    recognitionSession = RecognitionSession(),
                    recognitionSessionBusy = false,
                    recognitionSessionError = null,
                    orders = emptyList(),
                    ordersLoading = false,
                    ordersError = null,
                    ordersUpdatedAt = null,
                    orderActionId = null,
                    orderVerificationResult = null,
                    orderVerificationOrderId = null,
                )
            }
        }
    }

    fun connectOrDisconnect() {
        val state = _uiState.value
        if (state.connectionPhase != ConnectionPhase.DISCONNECTED) {
            disconnect()
            return
        }

        val host = state.host.trim()
        val controlPort = ControlRules.parsePort(state.controlPort)
        val facePort = ControlRules.parsePort(state.facePort)
        when {
            !ControlRules.isValidHost(host) -> showMessage("请输入正确的 Jetson IP 或主机名")
            controlPort == null -> showMessage("控制端口必须是 1～65535")
            facePort == null -> showMessage("人脸端口必须是 1～65535")
            else -> {
                _uiState.update {
                    it.copy(
                        host = host,
                        protocolAccepted = false,
                        robot = RobotState(),
                    )
                }
                client.connect(host, controlPort)
            }
        }
    }

    fun selectSpeed(speed: Int) {
        if (speed in ControlRules.speeds && activeDriveId == null) {
            _uiState.update { it.copy(selectedSpeed = speed) }
        }
    }

    fun selectDuration(durationMs: Int) {
        if (durationMs in ControlRules.durationsMs && activeDriveId == null) {
            _uiState.update { it.copy(selectedDurationMs = durationMs) }
        }
    }

    fun pressDrive(command: DriveCommand) {
        val state = _uiState.value
        if (!state.canControl) {
            showMessage("底盘尚未就绪，不能发送运动命令")
            return
        }
        if (state.robot.followLine) {
            showMessage("请先关闭寻迹，再使用手动遥控")
            return
        }

        stopActiveDrive(sendPacket = true)
        val id = requestIds.getAndIncrement()
        val sent = client.send(
            ControlProtocol.drive(
                id = id,
                command = command,
                speed = state.selectedSpeed,
                durationMs = state.selectedDurationMs,
            ),
        )
        if (!sent) {
            showMessage("运动命令发送失败，请检查连接")
            return
        }

        activeDriveId = id
        _uiState.update { it.copy(activeCommand = command, message = "${command.label}命令已发送") }
        if (state.selectedDurationMs == 0) {
            startHeartbeat(id)
        } else {
            startTimedStop(id, state.selectedDurationMs)
        }
    }

    fun releaseDrive(command: DriveCommand) {
        val state = _uiState.value
        if (state.selectedDurationMs == 0 && state.activeCommand == command) {
            stopActiveDrive(sendPacket = true, message = "已松手停车")
        }
    }

    fun stop() {
        stopActiveDrive(sendPacket = true, message = "已停车")
    }

    fun setFollowLine(enabled: Boolean) {
        val state = _uiState.value
        if (!state.canControl) {
            showMessage("底盘尚未就绪，不能切换寻迹")
            return
        }
        stopActiveDrive(sendPacket = true)
        val id = requestIds.getAndIncrement()
        if (client.send(ControlProtocol.followLine(id, enabled))) {
            if (enabled) {
                followLineId = id
                followConfirmed = false
                startFollowHeartbeat(id)
            } else {
                stopFollowLease()
            }
            _uiState.update {
                it.copy(
                    robot = it.robot.copy(followLine = enabled),
                    message = if (enabled) "正在启用寻迹" else "正在关闭寻迹",
                )
            }
        } else {
            showMessage("寻迹命令发送失败")
        }
    }

    fun emergencyStop() {
        stopActiveDrive(sendPacket = false)
        stopFollowLease()
        val id = requestIds.getAndIncrement()
        val sent = client.send(ControlProtocol.emergencyStop(id))
        _uiState.update {
            it.copy(
                activeCommand = null,
                robot = it.robot.copy(moving = false, command = null, followLine = false),
                message = if (sent) "已发送全局急停" else "急停发送失败：控制通道未连接",
            )
        }
    }

    /** Activity 进入后台时调用；同时关闭寻迹并停车。 */
    fun stopForSafety() {
        stopMotionAndFollowLine("App 进入后台，已停车")
    }

    /** 离开遥控页面前调用，避免小车在控制按钮不可见时继续运动。 */
    fun stopBeforeLeavingControl() {
        stopMotionAndFollowLine("已离开控制页面，小车已停车")
    }

    private fun stopMotionAndFollowLine(message: String) {
        val state = _uiState.value
        if (state.connectionPhase == ConnectionPhase.CONNECTED) {
            if (state.robot.followLine || followLineId != null) {
                client.send(ControlProtocol.followLine(requestIds.getAndIncrement(), false))
            }
            stopFollowLease()
            stopActiveDrive(sendPacket = true, message = message)
            _uiState.update { it.copy(robot = it.robot.copy(followLine = false)) }
        }
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        facePollingJob?.cancel()
        facePollingJob = null
        ordersPollingJob?.cancel()
        ordersPollingJob = null
        if (!active) {
            faceRequestJob?.cancel()
            faceRequestJob = null
            recognitionSessionActionJob?.cancel()
            recognitionSessionActionJob = null
            recognitionSessionPollingJob?.cancel()
            recognitionSessionPollingJob = null
            ordersRequestJob?.cancel()
            ordersRequestJob = null
            orderActionJob?.cancel()
            orderActionJob = null
            _uiState.update {
                it.copy(
                    faceServiceLoading = false,
                    recognitionSessionBusy = false,
                    ordersLoading = false,
                    orderActionId = null,
                )
            }
            return
        }
        refreshFaceService()
        refreshOrders()
        facePollingJob = viewModelScope.launch {
            while (isActive && foreground) {
                delay(FACE_REFRESH_INTERVAL_MS)
                if (
                    faceRequestJob?.isActive != true &&
                    faceDeleteJob?.isActive != true &&
                    recognitionSessionActionJob?.isActive != true &&
                    !_uiState.value.recognitionSession.active
                ) {
                    refreshFaceService(showLoading = false)
                }
            }
        }
        ordersPollingJob = viewModelScope.launch {
            while (isActive && foreground) {
                delay(ORDERS_REFRESH_INTERVAL_MS)
                if (
                    ordersRequestJob?.isActive != true &&
                    orderActionJob?.isActive != true
                ) {
                    refreshOrders(showLoading = false)
                }
            }
        }
    }

    fun refreshFaceService(showLoading: Boolean = true) {
        if (
            faceRequestJob?.isActive == true ||
            faceDeleteJob?.isActive == true ||
            recognitionSessionActionJob?.isActive == true
        ) return
        val baseUrl = _uiState.value.faceBaseUrl
        if (showLoading) {
            _uiState.update { it.copy(faceServiceLoading = true, faceError = null) }
        }
        faceRequestJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { faceClient.load(baseUrl) }
            if (_uiState.value.faceBaseUrl != baseUrl) {
                _uiState.update { it.copy(faceServiceLoading = false) }
                return@launch
            }
            _uiState.update { state ->
                when (result) {
                    is FaceApiResult.Success -> state.copy(
                        faceServiceOnline = true,
                        faceServiceLoading = false,
                        faceCameraReady = result.value.cameraReady,
                        faceRecognitionReady = result.value.recognitionReady,
                        facePeopleCount = result.value.peopleCount,
                        faceSampleCount = result.value.sampleCount,
                        faceDatabaseRevision = result.value.databaseRevision,
                        facePeople = result.value.people,
                        faceError = null,
                        faceUpdatedAt = System.currentTimeMillis(),
                        recognitionSession = result.value.recognitionSession,
                    )
                    is FaceApiResult.Failure -> state.copy(
                        faceServiceOnline = false,
                        faceServiceLoading = false,
                        faceCameraReady = false,
                        faceRecognitionReady = false,
                        faceError = result.message,
                    )
                }
            }
            if (result is FaceApiResult.Success && result.value.recognitionSession.active) {
                startRecognitionSessionPolling()
            }
            if (result is FaceApiResult.Success) {
                handleFinishedOrderRecognition(result.value.recognitionSession)
            }
        }
    }

    fun refreshOrders(showLoading: Boolean = true) {
        if (ordersRequestJob?.isActive == true || orderActionJob?.isActive == true) return
        val baseUrl = _uiState.value.faceBaseUrl
        _uiState.update {
            it.copy(
                ordersLoading = true,
                ordersError = if (showLoading) null else it.ordersError,
            )
        }
        ordersRequestJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { faceClient.loadOrders(baseUrl) }
            ordersRequestJob = null
            if (_uiState.value.faceBaseUrl != baseUrl) {
                _uiState.update { it.copy(ordersLoading = false) }
                return@launch
            }
            _uiState.update { state ->
                when (result) {
                    is FaceApiResult.Success -> state.copy(
                        orders = result.value,
                        ordersLoading = false,
                        ordersError = null,
                        ordersUpdatedAt = System.currentTimeMillis(),
                    )
                    is FaceApiResult.Failure -> state.copy(
                        ordersLoading = false,
                        ordersError = result.message,
                    )
                }
            }
        }
    }

    fun startDelivery(orderId: String) {
        if (
            orderActionJob?.isActive == true ||
            ordersRequestJob?.isActive == true ||
            _uiState.value.currentDeliveryOrder != null
        ) return
        runOrderAction(orderId, start = true)
    }

    fun cancelOrder(orderId: String) {
        if (orderActionJob?.isActive == true || ordersRequestJob?.isActive == true) return
        runOrderAction(orderId, start = false)
    }

    private fun runOrderAction(orderId: String, start: Boolean) {
        val baseUrl = _uiState.value.faceBaseUrl
        _uiState.update { it.copy(orderActionId = orderId, ordersError = null) }
        orderActionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (start) {
                    faceClient.startDelivery(baseUrl, orderId)
                } else {
                    faceClient.cancelOrder(baseUrl, orderId)
                }
            }
            orderActionJob = null
            if (_uiState.value.faceBaseUrl != baseUrl) {
                _uiState.update { it.copy(orderActionId = null) }
                return@launch
            }
            when (result) {
                is FaceApiResult.Success -> {
                    _uiState.update { state ->
                        val updatedOrders = state.orders.map { order ->
                            if (order.orderId == result.value.orderId) result.value else order
                        }.let { orders ->
                            if (orders.none { it.orderId == result.value.orderId }) {
                                listOf(result.value) + orders
                            } else {
                                orders
                            }
                        }
                        state.copy(
                            orders = updatedOrders,
                            orderActionId = null,
                            ordersError = null,
                            orderVerificationResult = null,
                            orderVerificationOrderId = null,
                            openControlForOrderId = if (start) result.value.orderId else null,
                        )
                    }
                    refreshOrders(showLoading = false)
                }
                is FaceApiResult.Failure -> _uiState.update {
                    it.copy(orderActionId = null, ordersError = result.message)
                }
            }
        }
    }

    fun consumeOpenControlRequest() {
        _uiState.update { it.copy(openControlForOrderId = null) }
    }

    fun startRecognition() {
        val state = _uiState.value
        if (
            recognitionSessionActionJob?.isActive == true ||
            state.recognitionSession.active
        ) return
        if (!state.orderStateReliable) {
            _uiState.update {
                it.copy(recognitionSessionError = "订单状态未知，请先刷新订单")
            }
            return
        }
        if (!state.faceServiceOnline || !state.faceCameraReady || !state.faceRecognitionReady) {
            _uiState.update { it.copy(recognitionSessionError = "摄像头或识别引擎尚未就绪") }
            return
        }
        val baseUrl = state.faceBaseUrl
        val orderId = state.currentDeliveryOrder?.orderId
        _uiState.update {
            it.copy(
                recognitionSessionBusy = true,
                recognitionSessionError = null,
                orderVerificationResult = null,
                orderVerificationOrderId = orderId,
            )
        }
        recognitionSessionActionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                faceClient.startRecognitionSession(
                    baseUrl,
                    RECOGNITION_DURATION_SECONDS,
                    orderId,
                )
            }
            recognitionSessionActionJob = null
            if (_uiState.value.faceBaseUrl != baseUrl) {
                _uiState.update { it.copy(recognitionSessionBusy = false) }
                return@launch
            }
            when (result) {
                is FaceApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            faceServiceOnline = true,
                            recognitionSession = result.value,
                            recognitionSessionBusy = false,
                            recognitionSessionError = null,
                        )
                    }
                    handleFinishedOrderRecognition(result.value)
                    if (result.value.active) startRecognitionSessionPolling()
                }
                is FaceApiResult.Failure -> _uiState.update {
                    it.copy(
                        recognitionSessionBusy = false,
                        recognitionSessionError = result.message,
                    )
                }
            }
        }
    }

    fun cancelRecognition() {
        val state = _uiState.value
        if (recognitionSessionActionJob?.isActive == true || !state.recognitionSession.active) return
        val baseUrl = state.faceBaseUrl
        _uiState.update {
            it.copy(recognitionSessionBusy = true, recognitionSessionError = null)
        }
        recognitionSessionActionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                faceClient.cancelRecognitionSession(baseUrl)
            }
            recognitionSessionActionJob = null
            if (_uiState.value.faceBaseUrl != baseUrl) {
                _uiState.update { it.copy(recognitionSessionBusy = false) }
                return@launch
            }
            when (result) {
                is FaceApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            recognitionSession = result.value,
                            recognitionSessionBusy = false,
                            recognitionSessionError = null,
                        )
                    }
                    handleFinishedOrderRecognition(result.value)
                    if (!result.value.active) {
                        recognitionSessionPollingJob?.cancel()
                        recognitionSessionPollingJob = null
                    }
                }
                is FaceApiResult.Failure -> _uiState.update {
                    it.copy(
                        recognitionSessionBusy = false,
                        recognitionSessionError = result.message,
                    )
                }
            }
        }
    }

    private fun startRecognitionSessionPolling() {
        if (!foreground || !_uiState.value.recognitionSession.active) return
        if (recognitionSessionPollingJob?.isActive == true) return
        recognitionSessionPollingJob = viewModelScope.launch {
            while (isActive && foreground && _uiState.value.recognitionSession.active) {
                delay(RECOGNITION_POLL_INTERVAL_MS)
                if (recognitionSessionActionJob?.isActive == true) continue
                val baseUrl = _uiState.value.faceBaseUrl
                val result = withContext(Dispatchers.IO) {
                    faceClient.getRecognitionSession(baseUrl)
                }
                if (_uiState.value.faceBaseUrl != baseUrl) break
                when (result) {
                    is FaceApiResult.Success -> {
                        _uiState.update {
                            it.copy(
                                faceServiceOnline = true,
                                recognitionSession = result.value,
                                recognitionSessionError = null,
                            )
                        }
                        handleFinishedOrderRecognition(result.value)
                    }
                    is FaceApiResult.Failure -> _uiState.update {
                        it.copy(recognitionSessionError = result.message)
                    }
                }
            }
            recognitionSessionPollingJob = null
        }
    }

    private fun handleFinishedOrderRecognition(session: RecognitionSession) {
        val orderResult = session.orderResult ?: return
        if (session.active) return
        val eventId = session.sessionId ?: "${session.orderId}:${session.state.wireValue}"
        if (handledOrderRecognitionSessionId == eventId) return
        handledOrderRecognitionSessionId = eventId
        _uiState.update {
            it.copy(
                orderVerificationResult = orderResult,
                orderVerificationOrderId = session.orderId,
            )
        }
        refreshOrders(showLoading = false)
    }

    fun deleteFacePerson(name: String) {
        if (faceDeleteJob?.isActive == true || faceRequestJob?.isActive == true) return
        val baseUrl = _uiState.value.faceBaseUrl
        _uiState.update { it.copy(deletingFaceName = name, faceError = null) }
        faceDeleteJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { faceClient.deletePerson(baseUrl, name) }
            if (_uiState.value.faceBaseUrl != baseUrl) {
                faceDeleteJob = null
                _uiState.update { it.copy(deletingFaceName = null) }
                return@launch
            }
            when (result) {
                is FaceApiResult.Success -> {
                    faceDeleteJob = null
                    _uiState.update { state ->
                        val deleted = state.facePeople.firstOrNull { it.name == name }
                        state.copy(
                            deletingFaceName = null,
                            facePeople = state.facePeople.filterNot { it.name == name },
                            facePeopleCount = (state.facePeopleCount - 1).coerceAtLeast(0),
                            faceSampleCount = (
                                state.faceSampleCount - (deleted?.samples ?: 0)
                                ).coerceAtLeast(0),
                            faceDatabaseRevision = state.faceDatabaseRevision + 1,
                            faceUpdatedAt = System.currentTimeMillis(),
                        )
                    }
                    refreshFaceService()
                }
                is FaceApiResult.Failure -> {
                    faceDeleteJob = null
                    _uiState.update {
                        it.copy(deletingFaceName = null, faceError = result.message)
                    }
                }
            }
        }
    }

    fun disconnect() {
        val stopId = activeDriveId ?: requestIds.getAndIncrement()
        if (_uiState.value.robot.followLine || followLineId != null) {
            client.send(ControlProtocol.followLine(requestIds.getAndIncrement(), false))
        }
        stopActiveDrive(sendPacket = false)
        stopFollowLease()
        client.disconnect(stopId)
        _uiState.update {
            it.copy(
                connectionPhase = ConnectionPhase.DISCONNECTED,
                protocolAccepted = false,
                activeCommand = null,
                robot = RobotState(),
                message = "已断开",
            )
        }
    }

    override fun onConnectionChanged(phase: ConnectionPhase, message: String) {
        viewModelScope.launch {
            if (phase == ConnectionPhase.DISCONNECTED) {
                stopActiveDrive(sendPacket = false)
                stopFollowLease()
            }
            _uiState.update {
                it.copy(
                    connectionPhase = phase,
                    protocolAccepted = if (phase == ConnectionPhase.CONNECTED) it.protocolAccepted else false,
                    activeCommand = if (phase == ConnectionPhase.DISCONNECTED) null else it.activeCommand,
                    robot = if (phase == ConnectionPhase.DISCONNECTED) RobotState() else it.robot,
                    message = message,
                )
            }
        }
    }

    override fun onServerMessage(message: ServerMessage) {
        viewModelScope.launch {
            when (message) {
                is ServerMessage.Hello -> {
                    if (message.protocol != CONTROL_PROTOCOL_VERSION) {
                        showMessage("协议版本不兼容：小车为 ${message.protocol}，App 为 $CONTROL_PROTOCOL_VERSION")
                        disconnect()
                    } else {
                        _uiState.update {
                            it.copy(
                                protocolAccepted = true,
                                robot = message.state ?: it.robot,
                                message = message.state?.message ?: message.message,
                            )
                        }
                    }
                }

                is ServerMessage.State -> {
                    if (message.value.followLine && followLineId != null) {
                        followConfirmed = true
                    } else if (!message.value.followLine && followConfirmed) {
                        stopFollowLease()
                    }
                    val effectiveState = if (
                        followLineId != null && !followConfirmed && !message.value.followLine
                    ) {
                        message.value.copy(followLine = true)
                    } else {
                        message.value
                    }
                    _uiState.update {
                        it.copy(robot = effectiveState, message = effectiveState.message)
                    }
                }

                is ServerMessage.Response -> {
                    if (!message.ok && message.id == activeDriveId) {
                        stopActiveDrive(sendPacket = false)
                    }
                    if (!message.ok && message.id == followLineId) {
                        stopFollowLease()
                        _uiState.update { it.copy(robot = it.robot.copy(followLine = false)) }
                    }
                    showMessage(message.message)
                }

                is ServerMessage.Unknown -> showMessage("收到未知消息：${message.op.ifBlank { "未指定 op" }}")
            }
        }
    }

    override fun onCleared() {
        val stopId = activeDriveId ?: requestIds.getAndIncrement()
        if (_uiState.value.robot.followLine || followLineId != null) {
            client.send(ControlProtocol.followLine(requestIds.getAndIncrement(), false))
        }
        stopActiveDrive(sendPacket = false)
        stopFollowLease()
        facePollingJob?.cancel()
        faceRequestJob?.cancel()
        faceDeleteJob?.cancel()
        recognitionSessionActionJob?.cancel()
        recognitionSessionPollingJob?.cancel()
        ordersRequestJob?.cancel()
        ordersPollingJob?.cancel()
        orderActionJob?.cancel()
        faceClient.close()
        client.disconnect(stopId)
        super.onCleared()
    }

    private fun startHeartbeat(id: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (isActive && activeDriveId == id) {
                delay(200)
                if (activeDriveId == id && !client.send(ControlProtocol.heartbeat(id))) {
                    stopActiveDrive(sendPacket = false, message = "续租失败，已结束本次控制")
                }
            }
        }
    }

    private fun startTimedStop(id: Long, durationMs: Int) {
        timedStopJob?.cancel()
        timedStopJob = viewModelScope.launch {
            delay(durationMs.toLong())
            if (activeDriveId == id) {
                activeDriveId = null
                _uiState.update { it.copy(activeCommand = null, message = "定时运动结束，已停车") }
                client.send(ControlProtocol.stop(id))
            }
        }
    }

    private fun startFollowHeartbeat(id: Long) {
        followHeartbeatJob?.cancel()
        followHeartbeatJob = viewModelScope.launch {
            while (isActive && followLineId == id) {
                delay(200)
                if (followLineId == id && !client.send(ControlProtocol.heartbeat(id))) {
                    stopFollowLease()
                    _uiState.update {
                        it.copy(
                            robot = it.robot.copy(followLine = false),
                            message = "寻迹续租失败，已结束寻迹",
                        )
                    }
                }
            }
        }
    }

    private fun stopFollowLease() {
        followLineId = null
        followConfirmed = false
        followHeartbeatJob?.cancel()
        followHeartbeatJob = null
    }

    private fun stopActiveDrive(
        sendPacket: Boolean,
        message: String? = null,
    ) {
        val id = activeDriveId
        activeDriveId = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        timedStopJob?.cancel()
        timedStopJob = null
        if (sendPacket) {
            client.send(ControlProtocol.stop(id ?: requestIds.getAndIncrement()))
        }
        _uiState.update { state ->
            state.copy(
                activeCommand = null,
                message = message ?: state.message,
            )
        }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    private companion object {
        const val FACE_REFRESH_INTERVAL_MS = 5_000L
        const val RECOGNITION_POLL_INTERVAL_MS = 500L
        const val ORDERS_REFRESH_INTERVAL_MS = 5_000L
        const val RECOGNITION_DURATION_SECONDS = 5
    }
}
