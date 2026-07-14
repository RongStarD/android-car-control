package cn.edu.xxq.carcontrol.mapping

data class MappingEndpoint(
    val host: String = "10.224.104.165",
    val port: String = "9092"
)

data class OccupancyGrid(
    val width: Int,
    val height: Int,
    val resolution: Float,
    val originX: Float,
    val originY: Float,
    val cells: ByteArray
)

data class MappingState(
    val endpoint: MappingEndpoint = MappingEndpoint(),
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val mappingActive: Boolean = false,
    val map: OccupancyGrid? = null,
    val scanSamples: Int = 0,
    val feedback: String = "未连接 Jetson 建图服务"
)
