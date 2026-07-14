package cn.edu.xxq.carappv2.model

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatingModeTest {
    @Test
    fun parsesKnownRuntimeModes() {
        assertEquals(OperatingMode.Idle, OperatingMode.fromWire("idle"))
        assertEquals(OperatingMode.Mapping, OperatingMode.fromWire("mapping"))
        assertEquals(OperatingMode.Navigation, OperatingMode.fromWire("navigation"))
    }

    @Test
    fun returnsUnknownForMissingOrUnsupportedMode() {
        assertEquals(OperatingMode.Unknown, OperatingMode.fromWire(null))
        assertEquals(OperatingMode.Unknown, OperatingMode.fromWire("future_mode"))
    }
}
