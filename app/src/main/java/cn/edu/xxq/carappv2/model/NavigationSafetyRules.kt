package cn.edu.xxq.carappv2.model

data class ValidatedNavigationSelections(
    val initialPose: RobotPose?,
    val goalPose: RobotPose?,
    val waypoints: List<RobotPose>,
    val initialPoseRemoved: Boolean,
    val goalPoseRemoved: Boolean,
    val removedWaypointCount: Int
) {
    val changed: Boolean
        get() = initialPoseRemoved || goalPoseRemoved || removedWaypointCount > 0
}

/** Pure safety rules shared by the navigation ViewModel and local unit tests. */
object NavigationSafetyRules {
    private val settledGoalStates = setOf(
        "none",
        "succeeded",
        "canceled",
        "aborted",
        "rejected",
        "error"
    )

    fun isPoseSelectable(
        staticMap: OccupancyGridMap?,
        globalCostmap: OccupancyGridMap?,
        pose: RobotPose
    ): Boolean {
        if (staticMap == null || MapTransform.selectionStatusAt(staticMap, pose.x, pose.y) !=
            MapSelectionStatus.Free
        ) {
            return false
        }
        return globalCostmap == null ||
            MapTransform.selectionStatusAt(globalCostmap, pose.x, pose.y) == MapSelectionStatus.Free
    }

    fun sanitizeSelections(
        staticMap: OccupancyGridMap,
        globalCostmap: OccupancyGridMap?,
        initialPose: RobotPose?,
        goalPose: RobotPose?,
        waypoints: List<RobotPose>
    ): ValidatedNavigationSelections {
        val validInitialPose = initialPose?.takeIf {
            isPoseSelectable(staticMap, globalCostmap, it)
        }
        val validGoalPose = goalPose?.takeIf {
            isPoseSelectable(staticMap, globalCostmap, it)
        }
        val validWaypoints = waypoints.filter {
            isPoseSelectable(staticMap, globalCostmap, it)
        }
        return ValidatedNavigationSelections(
            initialPose = validInitialPose,
            goalPose = validGoalPose,
            waypoints = validWaypoints,
            initialPoseRemoved = initialPose != null && validInitialPose == null,
            goalPoseRemoved = goalPose != null && validGoalPose == null,
            removedWaypointCount = waypoints.size - validWaypoints.size
        )
    }

    fun hasInitialPoseConflict(state: CarUiState): Boolean =
        state.navigationActive ||
            state.navigationRouteActive ||
            state.navigationTakeoverPending ||
            state.remoteControlArmed ||
            state.navigationGoalStateUncertain ||
            state.navigationAction != NavigationAction.None ||
            state.navigationGoalState.trim().lowercase() !in settledGoalStates

    fun hasGoalDispatchConflict(
        state: CarUiState,
        hasUnconfirmedGoalRequest: Boolean
    ): Boolean =
        state.navigationActive ||
            state.navigationRouteActive ||
            state.navigationTakeoverPending ||
            state.remoteControlArmed ||
            state.navigationGoalStateUncertain ||
            state.navigationAction != NavigationAction.None ||
            hasUnconfirmedGoalRequest ||
            state.navigationGoalState.trim().lowercase() !in settledGoalStates
}
