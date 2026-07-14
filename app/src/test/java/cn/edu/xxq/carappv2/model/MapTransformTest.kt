package cn.edu.xxq.carappv2.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MapTransformTest {
    private val map = OccupancyGridMap(
        width = 4,
        height = 2,
        resolution = 0.5f,
        originX = -1f,
        originY = -2f,
        cells = ByteArray(8)
    )

    @Test
    fun fitsMapAtCanvasCenterWithoutDistortingAspectRatio() {
        val viewport = MapTransform.fit(map, canvasWidth = 200f, canvasHeight = 200f)

        assertEquals(0f, viewport.left, DELTA)
        assertEquals(50f, viewport.top, DELTA)
        assertEquals(50f, viewport.scale, DELTA)
        assertEquals(200f, viewport.renderedWidth, DELTA)
        assertEquals(100f, viewport.renderedHeight, DELTA)
    }

    @Test
    fun convertsBottomLeftRosCoordinatesToTopLeftCanvasCoordinates() {
        val viewport = MapTransform.fit(map, 200f, 200f)

        val bottomLeft = MapTransform.worldToCanvas(map, viewport, -1f, -2f)
        val topRight = MapTransform.worldToCanvas(map, viewport, 1f, -1f)
        val centre = MapTransform.worldToCanvas(map, viewport, 0f, -1.5f)

        assertPoint(bottomLeft, 0f, 150f)
        assertPoint(topRight, 200f, 50f)
        assertPoint(centre, 100f, 100f)
    }

    @Test
    fun canvasToWorldIsInverseOfWorldToCanvas() {
        val viewport = MapTransform.fit(map, 320f, 180f, padding = 8f)
        val canvas = MapTransform.worldToCanvas(map, viewport, -0.35f, -1.25f)
        val world = MapTransform.canvasToWorld(map, viewport, canvas.x, canvas.y)

        assertEquals(-0.35f, world.x, DELTA)
        assertEquals(-1.25f, world.y, DELTA)
    }

    @Test
    fun locatesCellCentresAndFlipsRasterRows() {
        val viewport = MapTransform.fit(map, 200f, 200f)

        assertPoint(MapTransform.cellCenterToCanvas(map, viewport, 0, 0), 25f, 125f)
        assertPoint(MapTransform.cellCenterToCanvas(map, viewport, 3, 1), 175f, 75f)
        assertEquals(1, MapTransform.rasterRowForGridY(map, 0))
        assertEquals(0, MapTransform.rasterRowForGridY(map, 1))
    }

    @Test
    fun convertsNavigationPathFromMapFrameToCanvasCoordinates() {
        val viewport = MapTransform.fit(map, 200f, 200f)
        val path = NavigationPath(
            points = listOf(
                NavigationPathPoint(x = -1f, y = -2f, yaw = 0f),
                NavigationPathPoint(x = 0f, y = -1.5f, yaw = 0.5f),
                NavigationPathPoint(x = 1f, y = -1f, yaw = 1f)
            ),
            revision = 4L
        )

        val canvasPoints = MapTransform.pathToCanvas(map, viewport, path)

        assertEquals(3, canvasPoints.size)
        assertPoint(canvasPoints[0], 0f, 150f)
        assertPoint(canvasPoints[1], 100f, 100f)
        assertPoint(canvasPoints[2], 200f, 50f)
    }

    @Test
    fun convertsWorldCoordinatesToCellsAndExcludesMaximumEdges() {
        assertEquals(MapCellPoint(0, 0), MapTransform.worldToCellOrNull(map, -1f, -2f))
        assertEquals(MapCellPoint(3, 1), MapTransform.worldToCellOrNull(map, 0.999f, -1.001f))

        assertEquals(null, MapTransform.worldToCellOrNull(map, -1.001f, -2f))
        assertEquals(null, MapTransform.worldToCellOrNull(map, -1f, -2.001f))
        assertEquals(null, MapTransform.worldToCellOrNull(map, 1f, -1.5f))
        assertEquals(null, MapTransform.worldToCellOrNull(map, 0f, -1f))
    }

    @Test
    fun acceptsOnlyKnownFreeCellsForPoseSelection() {
        val selectionMap = OccupancyGridMap(
            width = 4,
            height = 1,
            resolution = 1f,
            originX = 0f,
            originY = 0f,
            cells = byteArrayOf(0, -1, 1, 100)
        )

        assertEquals(
            MapSelectionStatus.Free,
            MapTransform.selectionStatusAt(selectionMap, 0.5f, 0.5f)
        )
        assertEquals(
            MapSelectionStatus.Unknown,
            MapTransform.selectionStatusAt(selectionMap, 1.5f, 0.5f)
        )
        assertEquals(
            MapSelectionStatus.Occupied,
            MapTransform.selectionStatusAt(selectionMap, 2.5f, 0.5f)
        )
        assertEquals(
            MapSelectionStatus.Occupied,
            MapTransform.selectionStatusAt(selectionMap, 3.5f, 0.5f)
        )
        assertEquals(
            MapSelectionStatus.Outside,
            MapTransform.selectionStatusAt(selectionMap, 4f, 0.5f)
        )
    }

    @Test
    fun rejectsInvalidCanvasAndOutOfBoundsCells() {
        assertThrows(IllegalArgumentException::class.java) {
            MapTransform.fit(map, canvasWidth = 0f, canvasHeight = 100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapTransform.fit(map, canvasWidth = 100f, canvasHeight = 100f, padding = 50f)
        }
        val viewport = MapTransform.fit(map, 100f, 100f)
        assertThrows(IllegalArgumentException::class.java) {
            MapTransform.cellCenterToCanvas(map, viewport, 4, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapTransform.worldToCellOrNull(map, Float.NaN, 0f)
        }
    }

    private fun assertPoint(point: MapCanvasPoint, expectedX: Float, expectedY: Float) {
        assertEquals(expectedX, point.x, DELTA)
        assertEquals(expectedY, point.y, DELTA)
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
