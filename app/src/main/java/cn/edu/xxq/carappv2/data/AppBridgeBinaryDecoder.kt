package cn.edu.xxq.carappv2.data

import cn.edu.xxq.carappv2.model.LaserScanFrame
import cn.edu.xxq.carappv2.model.NavigationPath
import cn.edu.xxq.carappv2.model.NavigationPathPoint
import cn.edu.xxq.carappv2.model.OccupancyGridMap
import cn.edu.xxq.carappv2.model.RobotPose
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Inflater

sealed interface BridgeBinaryMessage {
    data class MapGrid(val map: OccupancyGridMap) : BridgeBinaryMessage
    data class Pose(val pose: RobotPose) : BridgeBinaryMessage
    data class Scan(val scan: LaserScanFrame) : BridgeBinaryMessage
    data class LocalCostmap(val map: OccupancyGridMap) : BridgeBinaryMessage
    data class GlobalCostmap(val map: OccupancyGridMap) : BridgeBinaryMessage
    data class GlobalPath(val path: NavigationPath) : BridgeBinaryMessage
    data class LocalPath(val path: NavigationPath) : BridgeBinaryMessage
    data class Unsupported(val packetType: Int, val payloadSize: Int) : BridgeBinaryMessage
}

/** Decoder for the compact, big-endian ICAR WebSocket protocol used on port 9092. */
object AppBridgeBinaryDecoder {
    private val magic = byteArrayOf(0x49, 0x43, 0x41, 0x52) // "ICAR"

    private const val PACKET_MAP = 1
    private const val PACKET_POSE = 2
    private const val PACKET_SCAN = 3
    private const val PACKET_LOCAL_COSTMAP = 4
    private const val PACKET_GLOBAL_COSTMAP = 5
    private const val PACKET_GLOBAL_PATH = 6
    private const val PACKET_LOCAL_PATH = 7
    private const val MAP_METADATA_SIZE = 28
    private const val POSE_PAYLOAD_SIZE = 12
    private const val SCAN_METADATA_SIZE = 10
    private const val PATH_METADATA_SIZE = 2
    private const val PATH_POINT_SIZE = 12
    private const val MAX_GRID_CELLS = 16_000_000
    private const val MAX_PATH_POINTS = 512

    fun decode(payload: ByteArray, revision: Long = 0L): BridgeBinaryMessage {
        require(payload.size >= 5) { "AppBridge 二进制包过短" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size)
        buffer.get(actualMagic)
        require(actualMagic.contentEquals(magic)) { "AppBridge 二进制包标识无效" }

        return when (val packetType = buffer.get().toInt() and 0xff) {
            PACKET_MAP -> BridgeBinaryMessage.MapGrid(decodeGrid(buffer, revision))
            PACKET_POSE -> BridgeBinaryMessage.Pose(decodePose(buffer))
            PACKET_SCAN -> BridgeBinaryMessage.Scan(decodeScan(buffer))
            PACKET_LOCAL_COSTMAP -> BridgeBinaryMessage.LocalCostmap(decodeGrid(buffer, revision))
            PACKET_GLOBAL_COSTMAP -> BridgeBinaryMessage.GlobalCostmap(decodeGrid(buffer, revision))
            PACKET_GLOBAL_PATH -> BridgeBinaryMessage.GlobalPath(decodePath(buffer, revision))
            PACKET_LOCAL_PATH -> BridgeBinaryMessage.LocalPath(decodePath(buffer, revision))
            else -> BridgeBinaryMessage.Unsupported(packetType, buffer.remaining())
        }
    }

    private fun decodeGrid(buffer: ByteBuffer, revision: Long): OccupancyGridMap {
        require(buffer.remaining() >= MAP_METADATA_SIZE) { "地图包元数据不完整" }
        val width = buffer.int
        val height = buffer.int
        val resolution = buffer.float
        val originX = buffer.float
        val originY = buffer.float
        val rawSize = buffer.int
        val compressedSize = buffer.int

        val cellCount = width.toLong() * height.toLong()
        require(width > 0 && height > 0 && cellCount in 1..MAX_GRID_CELLS.toLong()) {
            "地图尺寸无效"
        }
        require(rawSize.toLong() == cellCount) { "地图原始数据长度与宽高不一致" }
        require(resolution.isFinite() && resolution > 0f) { "地图分辨率无效" }
        require(originX.isFinite() && originY.isFinite()) { "地图原点无效" }
        require(compressedSize >= 0 && compressedSize == buffer.remaining()) {
            "地图压缩数据长度无效"
        }

        val compressed = ByteArray(compressedSize)
        buffer.get(compressed)
        val cells = inflate(compressed, rawSize)
        require(cells.all { it.toInt() == -1 || it.toInt() in 0..100 }) {
            "地图包含无效占用值"
        }
        return OccupancyGridMap(
            width = width,
            height = height,
            resolution = resolution,
            originX = originX,
            originY = originY,
            cells = cells,
            revision = revision
        )
    }

    private fun decodePose(buffer: ByteBuffer): RobotPose {
        require(buffer.remaining() == POSE_PAYLOAD_SIZE) { "位姿包长度无效" }
        return RobotPose(
            x = buffer.float,
            y = buffer.float,
            yaw = buffer.float
        )
    }

    private fun decodeScan(buffer: ByteBuffer): LaserScanFrame {
        require(buffer.remaining() >= SCAN_METADATA_SIZE) { "雷达包元数据不完整" }
        val sampleCount = buffer.short.toInt() and 0xffff
        val angleMin = buffer.float
        val angleIncrement = buffer.float
        require(buffer.remaining() == sampleCount * Float.SIZE_BYTES) { "雷达距离数据长度无效" }
        val ranges = FloatArray(sampleCount) { buffer.float }
        return LaserScanFrame(
            angleMin = angleMin,
            angleIncrement = angleIncrement,
            ranges = ranges
        )
    }

    private fun decodePath(buffer: ByteBuffer, revision: Long): NavigationPath {
        require(buffer.remaining() >= PATH_METADATA_SIZE) { "Navigation path metadata is truncated" }
        val pointCount = buffer.short.toInt() and 0xffff
        require(pointCount <= MAX_PATH_POINTS) { "Navigation path exceeds protocol point limit" }
        require(buffer.remaining() == pointCount * PATH_POINT_SIZE) {
            "Navigation path payload length does not match point count"
        }
        val points = List(pointCount) {
            NavigationPathPoint(
                x = buffer.float,
                y = buffer.float,
                yaw = buffer.float
            )
        }
        return NavigationPath(points = points, revision = revision)
    }

    private fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val output = ByteArray(expectedSize)
            var offset = 0
            while (!inflater.finished() && offset < output.size) {
                val count = inflater.inflate(output, offset, output.size - offset)
                if (count == 0) break
                offset += count
            }
            require(offset == expectedSize && inflater.finished() && inflater.remaining == 0) {
                "地图解压长度不匹配"
            }
            output
        } catch (error: DataFormatException) {
            throw IllegalArgumentException("地图压缩数据损坏", error)
        } finally {
            inflater.end()
        }
    }
}
