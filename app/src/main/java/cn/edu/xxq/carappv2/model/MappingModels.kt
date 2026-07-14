package cn.edu.xxq.carappv2.model

import kotlin.math.floor
import kotlin.math.min

/** An axis-aligned ROS OccupancyGrid received from the Jetson AppBridge. */
data class OccupancyGridMap(
    val width: Int,
    val height: Int,
    val resolution: Float,
    val originX: Float,
    val originY: Float,
    val cells: ByteArray,
    val revision: Long = 0L
) {
    init {
        require(width > 0 && height > 0) { "地图宽高必须大于 0" }
        require(width.toLong() * height.toLong() == cells.size.toLong()) {
            "地图单元数量与宽高不一致"
        }
        require(resolution.isFinite() && resolution > 0f) { "地图分辨率无效" }
        require(originX.isFinite() && originY.isFinite()) { "地图原点无效" }
        require(revision >= 0L) { "地图版本号不能为负数" }
    }

    /** Returns the signed ROS occupancy value: -1 unknown, 0 free, 100 occupied. */
    fun cellAt(x: Int, y: Int): Int {
        require(x in 0 until width && y in 0 until height) { "地图坐标越界" }
        return cells[y * width + x].toInt()
    }
}

data class RobotPose(
    val x: Float,
    val y: Float,
    val yaw: Float
) {
    init {
        require(x.isFinite() && y.isFinite() && yaw.isFinite()) { "小车位姿无效" }
    }
}

data class LaserScanFrame(
    val angleMin: Float,
    val angleIncrement: Float,
    val ranges: FloatArray
) {
    init {
        require(angleMin.isFinite()) { "雷达起始角无效" }
        require(angleIncrement.isFinite() && angleIncrement != 0f) { "雷达角度步长无效" }
        require(ranges.all { it.isFinite() && it >= 0f }) { "雷达距离数据无效" }
    }

    val sampleCount: Int
        get() = ranges.size
}

/** One map-frame sample from a Nav2 Path packet. Yaw is expressed in radians. */
data class NavigationPathPoint(
    val x: Float,
    val y: Float,
    val yaw: Float
) {
    init {
        require(x.isFinite() && y.isFinite() && yaw.isFinite()) {
            "Navigation path point must contain finite coordinates"
        }
    }
}

/** A down-sampled Nav2 path in the ROS map frame. An empty path is valid. */
data class NavigationPath(
    val points: List<NavigationPathPoint>,
    val revision: Long = 0L
) {
    init {
        require(revision >= 0L) { "Navigation path revision cannot be negative" }
    }

    val pointCount: Int
        get() = points.size
}

data class MapCanvasPoint(val x: Float, val y: Float)

data class MapWorldPoint(val x: Float, val y: Float)

data class MapCellPoint(val x: Int, val y: Int)

/** Result of validating a user-selected point against the saved static map. */
enum class MapSelectionStatus {
    Free,
    Unknown,
    Occupied,
    Outside
}

/** Geometry used to fit a ROS map into a Canvas while preserving its aspect ratio. */
data class MapViewport(
    val left: Float,
    val top: Float,
    /** Canvas pixels per OccupancyGrid cell. */
    val scale: Float,
    val renderedWidth: Float,
    val renderedHeight: Float
) {
    init {
        require(
            left.isFinite() && top.isFinite() && scale.isFinite() &&
                renderedWidth.isFinite() && renderedHeight.isFinite()
        ) { "地图视口包含无效数值" }
        require(scale > 0f && renderedWidth > 0f && renderedHeight > 0f) {
            "地图视口尺寸无效"
        }
    }
}

/** Pure coordinate helpers shared by Canvas rendering and unit tests. */
object MapTransform {
    fun fit(
        map: OccupancyGridMap,
        canvasWidth: Float,
        canvasHeight: Float,
        padding: Float = 0f
    ): MapViewport {
        require(canvasWidth.isFinite() && canvasWidth > 0f) { "Canvas 宽度无效" }
        require(canvasHeight.isFinite() && canvasHeight > 0f) { "Canvas 高度无效" }
        require(padding.isFinite() && padding >= 0f) { "Canvas 内边距无效" }

        val usableWidth = canvasWidth - padding * 2f
        val usableHeight = canvasHeight - padding * 2f
        require(usableWidth > 0f && usableHeight > 0f) { "Canvas 可用区域为空" }

        val scale = min(usableWidth / map.width, usableHeight / map.height)
        val renderedWidth = map.width * scale
        val renderedHeight = map.height * scale
        return MapViewport(
            left = padding + (usableWidth - renderedWidth) / 2f,
            top = padding + (usableHeight - renderedHeight) / 2f,
            scale = scale,
            renderedWidth = renderedWidth,
            renderedHeight = renderedHeight
        )
    }

    /**
     * Converts map-frame metres to Canvas pixels.
     * ROS maps use a bottom-left origin while Canvas Y increases downwards.
     */
    fun worldToCanvas(
        map: OccupancyGridMap,
        viewport: MapViewport,
        worldX: Float,
        worldY: Float
    ): MapCanvasPoint {
        require(worldX.isFinite() && worldY.isFinite()) { "世界坐标无效" }
        val gridX = (worldX - map.originX) / map.resolution
        val gridY = (worldY - map.originY) / map.resolution
        return MapCanvasPoint(
            x = viewport.left + gridX * viewport.scale,
            y = viewport.top + (map.height - gridY) * viewport.scale
        )
    }

    fun canvasToWorld(
        map: OccupancyGridMap,
        viewport: MapViewport,
        canvasX: Float,
        canvasY: Float
    ): MapWorldPoint {
        require(canvasX.isFinite() && canvasY.isFinite()) { "Canvas 坐标无效" }
        val gridX = (canvasX - viewport.left) / viewport.scale
        val gridY = map.height - (canvasY - viewport.top) / viewport.scale
        return MapWorldPoint(
            x = map.originX + gridX * map.resolution,
            y = map.originY + gridY * map.resolution
        )
    }

    /**
     * Converts map-frame metres to a grid cell, or returns null outside the half-open map bounds.
     * The maximum X/Y edge is deliberately excluded because it belongs to no OccupancyGrid cell.
     */
    fun worldToCellOrNull(
        map: OccupancyGridMap,
        worldX: Float,
        worldY: Float
    ): MapCellPoint? {
        require(worldX.isFinite() && worldY.isFinite()) { "世界坐标无效" }
        val gridX = (worldX - map.originX) / map.resolution
        val gridY = (worldY - map.originY) / map.resolution
        if (gridX < 0f || gridY < 0f || gridX >= map.width || gridY >= map.height) {
            return null
        }
        return MapCellPoint(
            x = floor(gridX.toDouble()).toInt(),
            y = floor(gridY.toDouble()).toInt()
        )
    }

    /**
     * Validates an initial pose, goal or waypoint against the static map.
     * Only a zero-valued cell is treated as selectable; unknown and any occupied probability are
     * rejected so a touch cannot silently place a Nav2 pose in a wall or unexplored area.
     */
    fun selectionStatusAt(
        map: OccupancyGridMap,
        worldX: Float,
        worldY: Float
    ): MapSelectionStatus {
        val cell = worldToCellOrNull(map, worldX, worldY)
            ?: return MapSelectionStatus.Outside
        return when (map.cellAt(cell.x, cell.y)) {
            0 -> MapSelectionStatus.Free
            -1 -> MapSelectionStatus.Unknown
            else -> MapSelectionStatus.Occupied
        }
    }

    fun cellCenterToCanvas(
        map: OccupancyGridMap,
        viewport: MapViewport,
        cellX: Int,
        cellY: Int
    ): MapCanvasPoint {
        require(cellX in 0 until map.width && cellY in 0 until map.height) {
            "地图单元坐标越界"
        }
        return worldToCanvas(
            map = map,
            viewport = viewport,
            worldX = map.originX + (cellX + 0.5f) * map.resolution,
            worldY = map.originY + (cellY + 0.5f) * map.resolution
        )
    }

    /** Converts every map-frame point in a Nav2 path to Canvas coordinates. */
    fun pathToCanvas(
        map: OccupancyGridMap,
        viewport: MapViewport,
        path: NavigationPath
    ): List<MapCanvasPoint> = path.points.map { point ->
        worldToCanvas(
            map = map,
            viewport = viewport,
            worldX = point.x,
            worldY = point.y
        )
    }

    /** Row in a top-to-bottom raster for a bottom-to-top ROS grid row. */
    fun rasterRowForGridY(map: OccupancyGridMap, gridY: Int): Int {
        require(gridY in 0 until map.height) { "地图行坐标越界" }
        return map.height - 1 - gridY
    }
}
