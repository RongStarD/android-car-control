package cn.edu.xxq.rosmastercontrol

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderDisplayFormatTest {
    @Test
    fun utcOrderTimeIsShownInDeviceTimeZone() {
        assertEquals(
            "2026-07-14 20:00:00",
            formatOrderTime("2026-07-14T12:00:00Z", ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun missingAndUnparseableTimesFallBackSafely() {
        assertEquals("时间未记录", formatOrderTime(null, ZoneId.of("Asia/Shanghai")))
        assertEquals(
            "2026-07-14 12:00:00",
            formatOrderTime("2026-07-14T12:00:00", ZoneId.of("Asia/Shanghai")),
        )
    }
}
