package cn.edu.xxq.carappv2.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.edu.xxq.carappv2.model.LaserScanFrame
import cn.edu.xxq.carappv2.model.MapTransform
import cn.edu.xxq.carappv2.model.MapViewport
import cn.edu.xxq.carappv2.model.MapSelectionStatus
import cn.edu.xxq.carappv2.model.NavigationPath
import cn.edu.xxq.carappv2.model.OccupancyGridMap
import cn.edu.xxq.carappv2.model.RobotPose
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun NavigationMapCanvas(
    map: OccupancyGridMap?,
    robotPose: RobotPose?,
    scan: LaserScanFrame?,
    globalCostmap: OccupancyGridMap?,
    localCostmap: OccupancyGridMap?,
    globalPath: NavigationPath?,
    localPath: NavigationPath?,
    initialPose: RobotPose?,
    goalPose: RobotPose?,
    waypoints: List<RobotPose>,
    selectionEnabled: Boolean,
    selectionHint: String,
    onMapTap: (x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectionFeedback by remember(selectionEnabled, map?.revision) {
        mutableStateOf<String?>(null)
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
        pan += panChange
    }

    Box(
        modifier = modifier
            .aspectRatio(1.12f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF0F4F8))
            .onSizeChanged { canvasSize = it }
            .pointerInput(
                map,
                globalCostmap?.revision,
                zoom,
                pan,
                selectionEnabled,
                canvasSize
            ) {
                detectTapGestures { tap ->
                    val currentMap = map ?: return@detectTapGestures
                    if (!selectionEnabled || canvasSize.width <= 0 || canvasSize.height <= 0) {
                        return@detectTapGestures
                    }
                    val fitted = MapTransform.fit(
                        map = currentMap,
                        canvasWidth = canvasSize.width.toFloat(),
                        canvasHeight = canvasSize.height.toFloat(),
                        padding = 8.dp.toPx()
                    )
                    val renderedWidth = fitted.renderedWidth * zoom
                    val renderedHeight = fitted.renderedHeight * zoom
                    val viewport = MapViewport(
                        left = (canvasSize.width - renderedWidth) / 2f + pan.x,
                        top = (canvasSize.height - renderedHeight) / 2f + pan.y,
                        scale = fitted.scale * zoom,
                        renderedWidth = renderedWidth,
                        renderedHeight = renderedHeight
                    )
                    if (
                        tap.x !in viewport.left..(viewport.left + viewport.renderedWidth) ||
                        tap.y !in viewport.top..(viewport.top + viewport.renderedHeight)
                    ) {
                        selectionFeedback = "请点击地图区域内的白色自由位置"
                        return@detectTapGestures
                    }
                    val world = MapTransform.canvasToWorld(
                        currentMap,
                        viewport,
                        tap.x,
                        tap.y
                    )
                    val staticStatus = MapTransform.selectionStatusAt(
                        currentMap,
                        world.x,
                        world.y
                    )
                    val liveCostStatus = globalCostmap?.let { costmap ->
                        MapTransform.selectionStatusAt(costmap, world.x, world.y)
                    }
                    val status = when {
                        staticStatus != MapSelectionStatus.Free -> staticStatus
                        liveCostStatus == MapSelectionStatus.Unknown -> MapSelectionStatus.Unknown
                        liveCostStatus == MapSelectionStatus.Occupied -> MapSelectionStatus.Occupied
                        else -> MapSelectionStatus.Free
                    }
                    when (status) {
                        MapSelectionStatus.Free -> {
                            selectionFeedback = null
                            onMapTap(world.x, world.y)
                        }

                        MapSelectionStatus.Unknown -> {
                            selectionFeedback = "灰色区域尚未建图，不能选择"
                        }

                        MapSelectionStatus.Occupied -> {
                            selectionFeedback = "该位置存在障碍，请选择白色自由区域"
                        }

                        MapSelectionStatus.Outside -> {
                            selectionFeedback = "请点击地图区域内的白色自由位置"
                        }
                    }
                }
            }
            .transformable(transformState)
    ) {
        if (map == null) {
            Text(
                "先读取保存地图，或启动 Nav2 等待 /map",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color(0xFF687687)
            )
        } else {
            val mapImage = remember(map.revision) { createNavigationBaseBitmap(map) }
            val globalCostImage = globalCostmap?.let {
                remember(it.revision) { createCostmapBitmap(it, Color(0xFFFF8F00)) }
            }
            val localCostImage = localCostmap?.let {
                remember(it.revision) { createCostmapBitmap(it, Color(0xFFE53935)) }
            }

            Canvas(Modifier.fillMaxSize()) {
                val fitted = MapTransform.fit(
                    map = map,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    padding = 8.dp.toPx()
                )
                val viewport = MapViewport(
                    left = (size.width - fitted.renderedWidth * zoom) / 2f + pan.x,
                    top = (size.height - fitted.renderedHeight * zoom) / 2f + pan.y,
                    scale = fitted.scale * zoom,
                    renderedWidth = fitted.renderedWidth * zoom,
                    renderedHeight = fitted.renderedHeight * zoom
                )

                drawImage(
                    image = mapImage,
                    dstOffset = IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt()),
                    dstSize = IntSize(
                        viewport.renderedWidth.roundToInt().coerceAtLeast(1),
                        viewport.renderedHeight.roundToInt().coerceAtLeast(1)
                    ),
                    filterQuality = FilterQuality.None
                )

                if (globalCostmap != null && globalCostImage != null) {
                    drawGridOverlay(map, viewport, globalCostmap, globalCostImage)
                }
                if (localCostmap != null && localCostImage != null) {
                    drawGridOverlay(map, viewport, localCostmap, localCostImage)
                }
                drawNavigationPath(map, viewport, globalPath, Color(0xFF1565C0), 3.dp.toPx())
                drawNavigationPath(map, viewport, localPath, Color(0xFF00A67A), 4.dp.toPx())
                drawLaserScan(map, viewport, robotPose, scan)
                initialPose?.let {
                    drawPoseMarker(map, viewport, it, Color(0xFF2E7D32), 7.dp.toPx())
                }
                waypoints.forEach {
                    drawPoseMarker(map, viewport, it, Color(0xFF8E24AA), 4.dp.toPx())
                }
                goalPose?.let {
                    drawPoseMarker(map, viewport, it, Color(0xFF7B1FA2), 8.dp.toPx())
                }
                robotPose?.let {
                    drawPoseMarker(map, viewport, it, Color(0xFFD32F2F), 6.dp.toPx())
                }
                drawRect(color = Color(0xFF9DB4CE), style = Stroke(width = 1.dp.toPx()))
            }

            if (selectionEnabled) {
                Text(
                    selectionHint,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                            RoundedCornerShape(bottomEnd = 10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (zoom != 1f || pan != Offset.Zero) {
                TextButton(
                    onClick = {
                        zoom = 1f
                        pan = Offset.Zero
                    },
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Text("复位")
                }
            }
            selectionFeedback?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.92f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

private fun DrawScope.drawGridOverlay(
    mainMap: OccupancyGridMap,
    viewport: MapViewport,
    overlay: OccupancyGridMap,
    image: androidx.compose.ui.graphics.ImageBitmap
) {
    val topLeft = MapTransform.worldToCanvas(
        mainMap,
        viewport,
        overlay.originX,
        overlay.originY + overlay.height * overlay.resolution
    )
    val bottomRight = MapTransform.worldToCanvas(
        mainMap,
        viewport,
        overlay.originX + overlay.width * overlay.resolution,
        overlay.originY
    )
    drawImage(
        image = image,
        dstOffset = IntOffset(
            minOf(topLeft.x, bottomRight.x).roundToInt(),
            minOf(topLeft.y, bottomRight.y).roundToInt()
        ),
        dstSize = IntSize(
            abs(bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(1),
            abs(bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(1)
        ),
        filterQuality = FilterQuality.None
    )
}

private fun DrawScope.drawNavigationPath(
    map: OccupancyGridMap,
    viewport: MapViewport,
    path: NavigationPath?,
    color: Color,
    width: Float
) {
    val points = path?.let { MapTransform.pathToCanvas(map, viewport, it) }.orEmpty()
    points.zipWithNext().forEach { (start, end) ->
        drawLine(
            color = color,
            start = Offset(start.x, start.y),
            end = Offset(end.x, end.y),
            strokeWidth = width
        )
    }
}

private fun DrawScope.drawLaserScan(
    map: OccupancyGridMap,
    viewport: MapViewport,
    pose: RobotPose?,
    scan: LaserScanFrame?
) {
    if (pose == null || scan == null) return
    scan.ranges.forEachIndexed { index, range ->
        if (range <= 0.05f || range > 12f) return@forEachIndexed
        val angle = pose.yaw + scan.angleMin + index * scan.angleIncrement
        val point = MapTransform.worldToCanvas(
            map,
            viewport,
            pose.x + range * cos(angle),
            pose.y + range * sin(angle)
        )
        if (
            point.x in viewport.left..(viewport.left + viewport.renderedWidth) &&
            point.y in viewport.top..(viewport.top + viewport.renderedHeight)
        ) {
            drawCircle(Color(0xFF039BE5), 1.3.dp.toPx(), Offset(point.x, point.y))
        }
    }
}

private fun DrawScope.drawPoseMarker(
    map: OccupancyGridMap,
    viewport: MapViewport,
    pose: RobotPose,
    color: Color,
    radius: Float
) {
    val center = MapTransform.worldToCanvas(map, viewport, pose.x, pose.y)
    val tip = Offset(
        x = center.x + cos(pose.yaw) * radius * 2.7f,
        y = center.y - sin(pose.yaw) * radius * 2.7f
    )
    drawCircle(Color.White, radius + 2.dp.toPx(), Offset(center.x, center.y))
    drawCircle(color, radius, Offset(center.x, center.y))
    drawLine(color, Offset(center.x, center.y), tip, 3.dp.toPx())
}

private fun createNavigationBaseBitmap(map: OccupancyGridMap) = Bitmap.createBitmap(
    IntArray(map.width * map.height).also { pixels ->
        for (gridY in 0 until map.height) {
            val rasterY = MapTransform.rasterRowForGridY(map, gridY)
            for (x in 0 until map.width) {
                val value = map.cellAt(x, gridY)
                pixels[rasterY * map.width + x] = when (value) {
                    -1 -> 0xFFE3E9F0.toInt()
                    0 -> 0xFFFFFFFF.toInt()
                    else -> {
                        val shade = (245 - value.coerceIn(0, 100) * 2.15f)
                            .roundToInt()
                            .coerceIn(30, 245)
                        (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
                    }
                }
            }
        }
    },
    map.width,
    map.height,
    Bitmap.Config.ARGB_8888
).asImageBitmap()

private fun createCostmapBitmap(map: OccupancyGridMap, color: Color) = Bitmap.createBitmap(
    IntArray(map.width * map.height).also { pixels ->
        val red = (color.red * 255).roundToInt()
        val green = (color.green * 255).roundToInt()
        val blue = (color.blue * 255).roundToInt()
        for (gridY in 0 until map.height) {
            val rasterY = MapTransform.rasterRowForGridY(map, gridY)
            for (x in 0 until map.width) {
                val value = map.cellAt(x, gridY)
                val alpha = if (value <= 0) 0 else (35 + value * 1.55f)
                    .roundToInt()
                    .coerceIn(35, 190)
                pixels[rasterY * map.width + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    },
    map.width,
    map.height,
    Bitmap.Config.ARGB_8888
).asImageBitmap()
