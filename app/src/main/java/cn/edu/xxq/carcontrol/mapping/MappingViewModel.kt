package cn.edu.xxq.carcontrol.mapping

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MappingViewModel(application: Application) : AndroidViewModel(application) {
    private val gateway = MappingGateway()
    val state: StateFlow<MappingState> = gateway.state

    fun connect(host: String, port: String) = gatewayAction { connect(MappingEndpoint(host, port)) }
    fun start() = gatewayAction { start() }
    fun save() = gatewayAction { save() }
    fun stop() = gatewayAction { stop() }
    fun disconnect() = gateway.disconnect()

    private fun gatewayAction(action: suspend MappingGateway.() -> MappingResult) = viewModelScope.launch {
        withContext(Dispatchers.IO) { gateway.action() }
    }

    override fun onCleared() = gateway.disconnect()
}
