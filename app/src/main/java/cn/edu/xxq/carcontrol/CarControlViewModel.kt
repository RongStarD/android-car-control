package cn.edu.xxq.carcontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.xxq.carcontrol.data.CarGateway
import cn.edu.xxq.carcontrol.data.EndpointStore
import cn.edu.xxq.carcontrol.data.RosBridgeGateway
import cn.edu.xxq.carcontrol.data.TcpCarGateway
import cn.edu.xxq.carcontrol.model.CarUiState
import cn.edu.xxq.carcontrol.model.ConnectionMode
import cn.edu.xxq.carcontrol.model.ControlMode
import cn.edu.xxq.carcontrol.model.DriveDirection
import cn.edu.xxq.carcontrol.model.Endpoint
import cn.edu.xxq.carcontrol.model.TransportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CarControlViewModel(application: Application) : AndroidViewModel(application) {
    private val endpointStore = EndpointStore(application)
    private var gateway: CarGateway = RosBridgeGateway()
    private val _state = MutableStateFlow(CarUiState(endpoint = endpointStore.read()))
    val state: StateFlow<CarUiState> = _state.asStateFlow()

    fun updateMode(mode: ConnectionMode) = updateEndpoint {
        it.copy(mode = mode, port = if (mode == ConnectionMode.Tcp) "6000" else "8081")
    }

    fun updateHost(value: String) = updateEndpoint { it.copy(host = value) }
    fun updatePort(value: String) = updateEndpoint { it.copy(port = value) }
    fun updateVideoPort(value: String) = updateEndpoint { it.copy(videoPort = value) }
    fun setControlMode(mode: ControlMode) = _state.update { it.copy(controlMode = mode) }
    fun setVideoVisible(visible: Boolean) = _state.update { it.copy(showVideo = visible) }

    fun connect() = viewModelScope.launch {
        val endpoint = _state.value.endpoint
        endpointStore.save(endpoint)
        _state.update { it.copy(connecting = true, connected = false, feedback = "正在连接...") }
        withContext(Dispatchers.IO) { gateway.disconnect() }
        gateway = if (endpoint.mode == ConnectionMode.Tcp) TcpCarGateway() else RosBridgeGateway()
        applyResult(withContext(Dispatchers.IO) { gateway.connect(endpoint) }, connectedOnSuccess = true)
        _state.update { it.copy(connecting = false) }
    }

    fun disconnect() = viewModelScope.launch {
        if (_state.value.connected) {
            applyResult(withContext(Dispatchers.IO) { gateway.button(DriveDirection.Stop) })
        }
        withContext(Dispatchers.IO) { gateway.disconnect() }
        _state.update { it.copy(connected = false, connecting = false, feedback = "已断开，并已请求停车") }
    }

    fun button(direction: DriveDirection) = send { button(direction) }
    fun joystick(x: Int, y: Int) = send { joystick(x, y) }
    fun setWheels(speeds: List<Int>) = send { wheels(speeds) }
    fun stop() = send { button(DriveDirection.Stop) }

    fun photo() = send { photo() }

    fun toggleRecording() = sendFeature(
        command = { recording(!_state.value.recording) },
        apply = { current, ok -> current.copy(recording = if (ok) !current.recording else current.recording) }
    )

    fun toggleTracking() = sendFeature(
        command = { tracking(!_state.value.trackingEnabled) },
        apply = { current, ok -> current.copy(trackingEnabled = if (ok) !current.trackingEnabled else current.trackingEnabled) }
    )

    fun updateWheel(index: Int, value: Float) {
        _state.update { current ->
            val speeds = current.wheelSpeeds.toMutableList().also { it[index] = value.toInt() }
            current.copy(wheelSpeeds = speeds)
        }
    }

    private fun updateEndpoint(transform: (Endpoint) -> Endpoint) {
        _state.update { it.copy(endpoint = transform(it.endpoint), connected = false, feedback = "设置已变更，请重新连接") }
    }

    private fun send(command: suspend CarGateway.() -> TransportResult) = viewModelScope.launch {
        if (!_state.value.connected) {
            _state.update { it.copy(feedback = "请先连接小车") }
            return@launch
        }
        applyResult(withContext(Dispatchers.IO) { gateway.command() })
    }

    private fun sendFeature(
        command: suspend CarGateway.() -> TransportResult,
        apply: (CarUiState, Boolean) -> CarUiState
    ) = viewModelScope.launch {
        if (!_state.value.connected) {
            _state.update { it.copy(feedback = "请先连接小车") }
            return@launch
        }
        val result = withContext(Dispatchers.IO) { gateway.command() }
        applyResult(result)
        _state.update { current -> apply(current, result.ok) }
    }

    private fun applyResult(result: TransportResult, connectedOnSuccess: Boolean = false) {
        _state.update { current ->
            current.copy(
                connected = when {
                    connectedOnSuccess -> result.ok
                    result.ok -> current.connected
                    else -> false
                },
                lastCommand = if (result.ok) result.message else current.lastCommand,
                lastFrame = result.frame.ifBlank { current.lastFrame },
                feedback = result.message
            )
        }
    }
}
