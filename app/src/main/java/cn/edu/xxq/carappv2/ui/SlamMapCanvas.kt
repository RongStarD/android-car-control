package cn.edu.xxq.carappv2.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import cn.edu.xxq.carappv2.model.LaserScanFrame
import cn.edu.xxq.carappv2.model.MapTransform
import cn.edu.xxq.carappv2.model.MapViewport
import cn.edu.xxq.carappv2.model.OccupancyGridMap
import cn.edu.xxq.carappv2.model.RobotPose
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun SlamMapCanvas(
    map: OccupancyGridMap?,
    pose: RobotPose?,
    scan: LaserScanFrame?,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
        pan += panChange
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF0F4F8))
            .transformable(transformState)
    ) {
        if (map == null) {
            Text(
                "启动 m1 后，地图将在这里实时显示",
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = Color(0xFF687687)
            )
        } else {
            val mapImage = remember(map.revision) { createMapBitmap(map) }
            Canvas(Modifier.fillMaxSize()) {
                val fitted = MapTransform.fit(
                    map = map,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    padding = 8.dp.toPx()
                )
                val renderedWidth = fitted.renderedWidth * zoom
                val renderedHeight = fitted.renderedHeight * zoom
                val viewport = MapViewport(
                    left = (size.width - renderedWidth) / 2f + pan.x,
                    top = (size.height - renderedHeight) / 2f + pan.y,
                    scale = fitted.scale * zoom,
                    renderedWidth = renderedWidth,
                    renderedHeight = renderedHeight
                )

                drawImage(
                    image = mapImage,
                    dstOffset = IntOffset(
                        viewport.left.roundToInt(),
                        viewport.top.roundToInt()
                    ),
                    dstSize = IntSize(
                        viewport.renderedWidth.roundToInt().coerceAtLeast(1),
                        viewport.renderedHeight.roundToInt().coerceAtLeast(1)
                    ),
                    filterQuality = FilterQuality.None
                )

                if (pose != null && scan != null) {
                    scan.ranges.forEachIndexed { index, range ->
                        if (range <= 0.05f || range > 12f) return@forEachIndexed
                        val angle = pose.yaw + scan.angleMin + index * scan.angleIncrement
                        val worldX = pose.x + range * cos(angle)
                        val worldY = pose.y + range * sin(angle)
                        val point = MapTransform.worldToCanvas(
                            map,
                            viewport,
                            worldX,
                            worldY
                        )
                        if (
                            point.x in viewport.left..(viewport.left + viewport.renderedWidth) &&
                            point.y in viewport.top..(viewport.top + viewport.renderedHeight)
                        ) {
                            drawCircle(
                                color = Color(0xFF1E88E5),
                                radius = 1.5.dp.toPx(),
                                center = Offset(point.x, point.y)
                            )
                        }
                    }
                }

                if (pose != null) {
                    val center = MapTransform.worldToCanvas(
                        map,
                        viewport,
                        pose.x,
                        pose.y
                    )
                    val directionLength = 18.dp.toPx()
                    val tip = Offset(
                        x = center.x + cos(pose.yaw) * directionLength,
                        y = center.y - sin(pose.yaw) * directionLength
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 8.dp.toPx(),
                        center = Offset(center.x, center.y)
                    )
                    drawCircle(
                        color = Color(0xFFE53935),
                        radius = 6.dp.toPx(),
                        center = Offset(center.x, center.y)
                    )
                    drawLine(
                        color = Color(0xFFE53935),
                        start = Offset(center.x, center.y),
                        end = tip,
                        strokeWidth = 3.dp.toPx()
                    )
                }

                drawRect(
                    color = Color(0xFF9DB4CE),
                    style = Stroke(width = 1.dp.toPx())
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
        }
    }
}

private fun createMapBitmap(map: OccupancyGridMap) = Bitmap.createBitmap(
    IntArray(map.width * map.height).also { pixels ->
        for (gridY in 0 until map.height) {
            val rasterY = MapTransform.rasterRowForGridY(map, gridY)
            for (x in 0 until map.width) {
                pixels[rasterY * map.width + x] = occupancyColor(
                    map.cellAt(x, gridY)
                )
            }
        }
    },
    map.width,
    map.height,
    Bitmap.Config.ARGB_8888
).asImageBitmap()

private fun occupancyColor(value: Int): Int = when (value) {
    -1 -> 0xFFE3E9F0.toInt()
    0 -> 0xFFFFFFFF.toInt()
    else -> {
        val shade = (245 - value.coerceIn(0, 100) * 2.15f)
            .roundToInt()
            .coerceIn(30, 245)
        (0xFF shl 24) or (shade shl 16) or (shade shl 8) or shade
    }
}
