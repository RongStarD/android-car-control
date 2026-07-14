package cn.edu.xxq.carappv2.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSafetyRulesTest {
    private val baselineState = CarUiState(
        connectionStatus = ConnectionStatus.Connected,
        currentMode = OperatingMode.Navigation,
        ready = true,
        navigationReady = true
    )

    @Test
    fun initialPoseIsLockedForEveryNavigationControlConflict() {
        assertFalse(NavigationSafetyRules.hasInitialPoseConflict(baselineState))
        assertTrue(
            NavigationSafetyRules.hasInitialPoseConflict(
                baselineState.copy(navigationActive = true)
            )
        )
        assertTrue(
            NavigationSafetyRules.hasInitialPoseConflict(
                baselineState.copy(navigationRouteActive = true)
            )
        )
        assertTrue(
            NavigationSafetyRules.hasInitialPoseConflict(
                baselineState.copy(navigationTakeoverPending = true)
            )
        )
        assertTrue(
            NavigationSafetyRules.hasInitialPoseConflict(
                baselineState.copy(remoteControlArmed = true)
            )
        )
        assertTrue(
            NavigationSafetyRules.hasInitialPoseConflict(
                baselineState.copy(navigationGoalStateUncertain = true)
            )
        )
    }

    @Test
    fun goalDispatchIsLockedByNavigationActiveEvenWhenGoalStateIsNone() {
        val skewedState = baselineState.copy(
            navigationActive = true,
            navigationGoalState = "none"
        )

        assertTrue(
            NavigationSafetyRules.hasGoalDispatchConflict(
                skewedState,
                hasUnconfirmedGoalRequest = false
            )
        )
        assertFalse(
            NavigationSafetyRules.hasGoalDispatchConflict(
                baselineState,
                hasUnconfirmedGoalRequest = false
            )
        )
    }

    @Test
    fun uncertainOrUnconfirmedGoalNeverUnlocksAnotherDispatch() {
        assertTrue(
            NavigationSafetyRules.hasGoalDispatchConflict(
                baselineState.copy(navigationGoalStateUncertain = true),
                hasUnconfirmedGoalRequest = false
            )
        )
        assertTrue(
            NavigationSafetyRules.hasGoalDispatchConflict(
                baselineState,
                hasUnconfirmedGoalRequest = true
            )
        )
        assertTrue(
            NavigationSafetyRules.hasGoalDispatchConflict(
                baselineState.copy(navigationGoalState = "unrecognized"),
                hasUnconfirmedGoalRequest = false
            )
        )
    }

    @Test
    fun refreshedMapRemovesUnknownOccupiedAndOutsideSelections() {
        val map = mapOf((-1).toByte(), 0, 100, 0)
        val initial = RobotPose(1.5f, 0.5f, 0f)
        val unknownGoal = RobotPose(0.5f, 0.5f, 0f)
        val occupiedWaypoint = RobotPose(2.5f, 0.5f, 0f)
        val freeWaypoint = RobotPose(3.5f, 0.5f, 0f)
        val outsideWaypoint = RobotPose(4.5f, 0.5f, 0f)

        val result = NavigationSafetyRules.sanitizeSelections(
            staticMap = map,
            globalCostmap = null,
            initialPose = initial,
            goalPose = unknownGoal,
            waypoints = listOf(occupiedWaypoint, freeWaypoint, outsideWaypoint)
        )

        assertEquals(initial, result.initialPose)
        assertNull(result.goalPose)
        assertEquals(listOf(freeWaypoint), result.waypoints)
        assertFalse(result.initialPoseRemoved)
        assertTrue(result.goalPoseRemoved)
        assertEquals(2, result.removedWaypointCount)
    }

    @Test
    fun globalCostmapMustAlsoConfirmFreeSpaceWhenPresent() {
        val staticMap = mapOf(0, 0)
        val globalCostmap = mapOf(100, 0)

        assertFalse(
            NavigationSafetyRules.isPoseSelectable(
                staticMap,
                globalCostmap,
                RobotPose(0.5f, 0.5f, 0f)
            )
        )
        assertTrue(
            NavigationSafetyRules.isPoseSelectable(
                staticMap,
                globalCostmap,
                RobotPose(1.5f, 0.5f, 0f)
            )
        )
    }

    private fun mapOf(vararg cells: Byte) = OccupancyGridMap(
        width = cells.size,
        height = 1,
        resolution = 1f,
        originX = 0f,
        originY = 0f,
        cells = cells
    )
}
