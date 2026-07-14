package cn.edu.xxq.rosmastercontrol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlUiStateTest {
    @Test
    fun orderStateMustLoadSuccessfullyBeforeRecognition() {
        assertFalse(ControlUiState().orderStateReliable)
        assertTrue(
            ControlUiState(
                ordersUpdatedAt = 1L,
                ordersLoading = false,
                ordersError = null,
                orderActionId = null,
            ).orderStateReliable,
        )
    }

    @Test
    fun staleErrorLoadingOrActionMakesOrderStateUnreliable() {
        val loaded = ControlUiState(ordersUpdatedAt = 1L)

        assertFalse(loaded.copy(ordersError = "连接失败").orderStateReliable)
        assertFalse(loaded.copy(ordersLoading = true).orderStateReliable)
        assertFalse(loaded.copy(orderActionId = "order-1").orderStateReliable)
    }
}
