package cn.edu.xxq.carappv2.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationGoalStateNormalizationTest {
    @Test
    fun normalizesBridgeAliasesUsedAtSafetyBoundaries() {
        assertEquals("none", normalizeNavigationGoalState(" IDLE "))
        assertEquals("pending", normalizeNavigationGoalState("pending_accept"))
        assertEquals("cancel_pending", normalizeNavigationGoalState("cancel_requested"))
        assertEquals("canceled", normalizeNavigationGoalState("stopped"))
        assertEquals("error", normalizeNavigationGoalState("failed"))
        assertEquals("error", normalizeNavigationGoalState("finished"))
        assertEquals("active", normalizeNavigationGoalState("ACTIVE"))
    }
}
