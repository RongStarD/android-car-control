package cn.edu.xxq.carappv2.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DeflaterOutputStream

class AppBridgeBinaryDecoderTest {
    @Test
    fun decodesCompressedOccupancyGridAndPreservesSignedCells() {
        val cells = byteArrayOf((-1).toByte(), 0, 50, 100)
        val decoded = AppBridgeBinaryDecoder.decode(
            mapPacket(
                width = 2,
                height = 2,
                resolution = 0.05f,
                originX = -1.25f,
                originY = 2.5f,
                cells = cells
            ),
            revision = 7L
        ) as BridgeBinaryMessage.MapGrid

        assertEquals(2, decoded.map.width)
        assertEquals(2, decoded.map.height)
        assertEquals(0.05f, decoded.map.resolution)
        assertEquals(-1.25f, decoded.map.originX)
        assertEquals(2.5f, decoded.map.originY)
        assertEquals(7L, decoded.map.revision)
        assertArrayEquals(cells, decoded.map.cells)
        assertEquals(-1, decoded.map.cellAt(0, 0))
        assertEquals(100, decoded.map.cellAt(1, 1))
    }

    @Test
    fun decodesRobotPose() {
        val packet = ByteBuffer.allocate(5 + 12).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(2)
            .putFloat(1.5f)
            .putFloat(-2.25f)
            .putFloat(0.75f)
            .array()

        val decoded = AppBridgeBinaryDecoder.decode(packet) as BridgeBinaryMessage.Pose

        assertEquals(1.5f, decoded.pose.x)
        assertEquals(-2.25f, decoded.pose.y)
        assertEquals(0.75f, decoded.pose.yaw)
    }

    @Test
    fun decodesLaserScanMetadataAndRanges() {
        val ranges = floatArrayOf(0f, 0.8f, 1.25f)
        val packet = ByteBuffer.allocate(5 + 10 + ranges.size * 4).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(3)
            .putShort(ranges.size.toShort())
            .putFloat(-1.57f)
            .putFloat(0.02f)
            .also { buffer -> ranges.forEach(buffer::putFloat) }
            .array()

        val decoded = AppBridgeBinaryDecoder.decode(packet) as BridgeBinaryMessage.Scan

        assertEquals(-1.57f, decoded.scan.angleMin)
        assertEquals(0.02f, decoded.scan.angleIncrement)
        assertEquals(3, decoded.scan.sampleCount)
        assertArrayEquals(ranges, decoded.scan.ranges, 0f)
    }

    @Test
    fun decodesLocalAndGlobalCostmapsWithoutChangingMainMapFormat() {
        val localCells = byteArrayOf((-1).toByte(), 0, 35, 100)
        val local = AppBridgeBinaryDecoder.decode(
            mapPacket(2, 2, 0.1f, -0.5f, -0.25f, localCells, type = 4),
            revision = 21L
        ) as BridgeBinaryMessage.LocalCostmap

        assertEquals(2, local.map.width)
        assertEquals(0.1f, local.map.resolution)
        assertEquals(21L, local.map.revision)
        assertArrayEquals(localCells, local.map.cells)

        val globalCells = byteArrayOf(0, 25, 75)
        val global = AppBridgeBinaryDecoder.decode(
            mapPacket(3, 1, 0.05f, -4f, 1.5f, globalCells, type = 5),
            revision = 22L
        ) as BridgeBinaryMessage.GlobalCostmap

        assertEquals(3, global.map.width)
        assertEquals(-4f, global.map.originX)
        assertEquals(22L, global.map.revision)
        assertArrayEquals(globalCells, global.map.cells)

        val main = AppBridgeBinaryDecoder.decode(
            mapPacket(1, 1, 0.05f, 0f, 0f, byteArrayOf(0)),
            revision = 23L
        )
        assertEquals(BridgeBinaryMessage.MapGrid::class.java, main::class.java)
    }

    @Test
    fun decodesGlobalAndLocalNavigationPaths() {
        val global = AppBridgeBinaryDecoder.decode(
            pathPacket(
                type = 6,
                points = listOf(
                    floatArrayOf(-1.25f, 2.5f, -0.75f),
                    floatArrayOf(3f, 4.5f, 1.57f)
                )
            ),
            revision = 31L
        ) as BridgeBinaryMessage.GlobalPath

        assertEquals(2, global.path.pointCount)
        assertEquals(-1.25f, global.path.points[0].x)
        assertEquals(2.5f, global.path.points[0].y)
        assertEquals(-0.75f, global.path.points[0].yaw)
        assertEquals(1.57f, global.path.points[1].yaw)
        assertEquals(31L, global.path.revision)

        val local = AppBridgeBinaryDecoder.decode(
            pathPacket(type = 7, points = emptyList()),
            revision = 32L
        ) as BridgeBinaryMessage.LocalPath

        assertEquals(0, local.path.pointCount)
        assertEquals(32L, local.path.revision)
    }

    @Test
    fun returnsUnsupportedForFuturePacketType() {
        val packet = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(99)
            .put(byteArrayOf(1, 2, 3))
            .array()

        assertEquals(
            BridgeBinaryMessage.Unsupported(packetType = 99, payloadSize = 3),
            AppBridgeBinaryDecoder.decode(packet)
        )
    }

    @Test
    fun rejectsInvalidMagicAndTruncatedPackets() {
        val invalidMagic = byteArrayOf(0x58, 0x43, 0x41, 0x52, 2)
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(invalidMagic)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(byteArrayOf(0x49, 0x43, 0x41))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(byteArrayOf(0x49, 0x43, 0x41, 0x52, 1))
        }
    }

    @Test
    fun rejectsMapWhoseInflatedLengthDoesNotMatchMetadata() {
        val compressed = compress(byteArrayOf(0, 100, 0))
        val packet = ByteBuffer.allocate(5 + 28 + compressed.size).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(1)
            .putInt(2)
            .putInt(2)
            .putFloat(0.05f)
            .putFloat(0f)
            .putFloat(0f)
            .putInt(4)
            .putInt(compressed.size)
            .put(compressed)
            .array()

        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(packet)
        }
    }

    @Test
    fun rejectsInvalidOccupancyValuesAndScanLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(
                mapPacket(1, 1, 0.05f, 0f, 0f, byteArrayOf(101))
            )
        }

        val truncatedScan = ByteBuffer.allocate(5 + 10 + 4).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(3)
            .putShort(2)
            .putFloat(0f)
            .putFloat(0.1f)
            .putFloat(1f)
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(truncatedScan)
        }
    }

    @Test
    fun rejectsDamagedAndTruncatedNavigationGridPackets() {
        val damaged = ByteBuffer.allocate(5 + 28 + 4).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(4)
            .putInt(1)
            .putInt(1)
            .putFloat(0.05f)
            .putFloat(0f)
            .putFloat(0f)
            .putInt(1)
            .putInt(4)
            .put(byteArrayOf(1, 2, 3, 4))
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(damaged)
        }

        val complete = mapPacket(2, 1, 0.05f, 0f, 0f, byteArrayOf(0, 100), type = 5)
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(complete.copyOf(complete.size - 1))
        }
    }

    @Test
    fun rejectsTruncatedOversizedAndNonFiniteNavigationPaths() {
        val truncatedMetadata = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(6)
            .put(1.toByte())
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(truncatedMetadata)
        }

        val truncatedPoint = ByteBuffer.allocate(5 + 2 + 12).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(7)
            .putShort(2)
            .putFloat(1f)
            .putFloat(2f)
            .putFloat(3f)
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(truncatedPoint)
        }

        val oversized = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(6)
            .putShort(513)
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(oversized)
        }

        val nonFinite = pathPacket(
            type = 7,
            points = listOf(floatArrayOf(Float.NaN, 0f, 0f))
        )
        assertThrows(IllegalArgumentException::class.java) {
            AppBridgeBinaryDecoder.decode(nonFinite)
        }
    }

    private fun mapPacket(
        width: Int,
        height: Int,
        resolution: Float,
        originX: Float,
        originY: Float,
        cells: ByteArray,
        type: Int = 1
    ): ByteArray {
        val compressed = compress(cells)
        return ByteBuffer.allocate(5 + 28 + compressed.size).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(type)
            .putInt(width)
            .putInt(height)
            .putFloat(resolution)
            .putFloat(originX)
            .putFloat(originY)
            .putInt(cells.size)
            .putInt(compressed.size)
            .put(compressed)
            .array()
    }

    private fun pathPacket(type: Int, points: List<FloatArray>): ByteArray {
        require(points.all { it.size == 3 })
        return ByteBuffer.allocate(5 + 2 + points.size * 12).order(ByteOrder.BIG_ENDIAN)
            .putMagicAndType(type)
            .putShort(points.size.toShort())
            .also { buffer ->
                points.forEach { point -> point.forEach(buffer::putFloat) }
            }
            .array()
    }

    private fun compress(raw: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output).use { it.write(raw) }
        return output.toByteArray()
    }

    private fun ByteBuffer.putMagicAndType(type: Int): ByteBuffer =
        put(byteArrayOf(0x49, 0x43, 0x41, 0x52)).put(type.toByte())
}
