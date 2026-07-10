package cn.edu.xxq.carcontrol.data

import android.content.Context
import cn.edu.xxq.carcontrol.model.ConnectionMode
import cn.edu.xxq.carcontrol.model.Endpoint

class EndpointStore(context: Context) {
    private val preferences = context.getSharedPreferences("car_connection", Context.MODE_PRIVATE)

    fun read(): Endpoint = Endpoint(
        mode = ConnectionMode.entries.firstOrNull { it.name == preferences.getString("mode", null) }
            ?: ConnectionMode.RosBridge,
        host = preferences.getString("host", "10.39.132.165") ?: "10.39.132.165",
        port = preferences.getString("port", "8081") ?: "8081",
        videoPort = preferences.getString("videoPort", "6500") ?: "6500"
    )

    fun save(endpoint: Endpoint) {
        preferences.edit()
            .putString("mode", endpoint.mode.name)
            .putString("host", endpoint.host)
            .putString("port", endpoint.port)
            .putString("videoPort", endpoint.videoPort)
            .apply()
    }
}
