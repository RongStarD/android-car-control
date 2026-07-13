package cn.edu.xxq.carcontrol.mapping

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MappingApp(viewModel: MappingViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var host by remember(state.endpoint.host) { mutableStateOf(state.endpoint.host) }
    var port by remember(state.endpoint.port) { mutableStateOf(state.endpoint.port) }
    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("激光 SLAM 建图", style = MaterialTheme.typography.headlineMedium)
            Text("地图显示对应 m2；GMapping 启动、保存和停止分别对应 m1、m4 与结束 m1。小车移动和自动导航由其他模块提供。")
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(host, { host = it }, label = { Text("Jetson IP") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(port, { port = it }, label = { Text("端口") }, modifier = Modifier.width(100.dp))
                    Button(onClick = { viewModel.connect(host, port) }, enabled = !state.connecting) { Text(if (state.connected) "重连" else "连接") }
                }
            }
            Text(if (state.mappingActive) "GMapping 正在建图" else "建图未启动", color = if (state.mappingActive) Color(0xFF0B6E4F) else MaterialTheme.colorScheme.onSurface)
            MapCanvas(state.map, Modifier.fillMaxWidth().height(300.dp))
            Text("${state.feedback}；雷达采样 ${state.scanSamples}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::start, enabled = state.connected && !state.mappingActive) { Text("开始建图") }
                Button(onClick = viewModel::save, enabled = state.connected && state.mappingActive) { Text("保存地图") }
                Button(onClick = viewModel::stop, enabled = state.connected && state.mappingActive) { Text("结束建图") }
            }
        }
    }
}

@Composable
private fun MapCanvas(map: OccupancyGrid?, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF202124))) {
        if (map == null) {
            drawContext.canvas.nativeCanvas.drawText("等待 /map 数据", 24f, 42f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 28f })
        } else drawGrid(map)
    }
}

private fun DrawScope.drawGrid(map: OccupancyGrid) {
    val scale = minOf(size.width / map.width, size.height / map.height)
    val left = (size.width - map.width * scale) / 2f
    val top = (size.height - map.height * scale) / 2f
    val step = maxOf(1, map.width / 360)
    for (y in 0 until map.height step step) for (x in 0 until map.width step step) {
        val value = map.cells[y * map.width + x].toInt()
        val color = when { value < 0 -> Color(0xFF555555); value > 50 -> Color(0xFF101010); else -> Color(0xFFF1F1F1) }
        drawRect(color, Offset(left + x * scale, top + y * scale), androidx.compose.ui.geometry.Size(step * scale + 1, step * scale + 1))
    }
}
