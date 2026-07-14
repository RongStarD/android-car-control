package cn.edu.xxq.rosmastercontrol.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProtocolTest {
    @Test
    fun drivePacketUsesDocumentedCommandCodes() {
        val json = JSONObject(ControlProtocol.drive(42, DriveCommand.STRAFE_LEFT, 70, 500))

        assertEquals("drive", json.getString("op"))
        assertEquals(42L, json.getLong("id"))
        assertEquals(3, json.getInt("command"))
        assertEquals(70, json.getInt("speed"))
        assertEquals(500, json.getInt("duration_ms"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun driveRejectsUnsupportedSpeed() {
        ControlProtocol.drive(1, DriveCommand.FORWARD, 80, 0)
    }

    @Test
    fun parsesCompleteState() {
        val message = ControlProtocol.parse(
            """{"op":"state","serial_ready":true,"camera_ready":false,"moving":true,"command":5,"speed":50,"duration_ms":1000,"follow_line":true,"message":"运行中"}""",
        ) as ServerMessage.State

        assertTrue(message.value.serialReady)
        assertFalse(message.value.cameraReady)
        assertTrue(message.value.moving)
        assertEquals(DriveCommand.TURN_LEFT, message.value.command)
        assertEquals(50, message.value.speed)
        assertEquals(1_000, message.value.durationMs)
        assertTrue(message.value.followLine)
        assertEquals("运行中", message.value.message)
    }

    @Test
    fun missingStateFieldsHaveSafeDefaults() {
        val state = (ControlProtocol.parse("""{"op":"state"}""") as ServerMessage.State).value

        assertFalse(state.serialReady)
        assertFalse(state.moving)
        assertNull(state.command)
        assertEquals(0, state.speed)
    }

    @Test
    fun helloCanCarryInitialState() {
        val hello = ControlProtocol.parse(
            """{"op":"hello","protocol":1,"state":{"serial_ready":true,"camera_ready":true,"message":"ready"}}""",
        ) as ServerMessage.Hello

        assertEquals(1, hello.protocol)
        assertTrue(hello.state?.serialReady == true)
        assertTrue(hello.state?.cameraReady == true)
        assertEquals("ready", hello.state?.message)
    }

    @Test
    fun safetyPacketsUseOriginalDriveId() {
        assertEquals("heartbeat", JSONObject(ControlProtocol.heartbeat(9)).getString("op"))
        assertEquals(9L, JSONObject(ControlProtocol.heartbeat(9)).getLong("id"))
        assertEquals("stop", JSONObject(ControlProtocol.stop(9)).getString("op"))
        assertEquals("emergency_stop", JSONObject(ControlProtocol.emergencyStop(10)).getString("op"))
    }

    @Test
    fun validatesEndpointAndDurationLabels() {
        assertTrue(ControlRules.isValidHost("10.39.132.165"))
        assertFalse(ControlRules.isValidHost("http://10.39.132.165"))
        assertEquals(9093, ControlRules.parsePort("9093"))
        assertNull(ControlRules.parsePort("70000"))
        assertEquals("持续", ControlRules.durationLabel(0))
        assertEquals("0.5s", ControlRules.durationLabel(500))
    }

    @Test
    fun commandCodesMatchTheActuallyLoadedDesktopApp() {
        assertEquals(1, DriveCommand.FORWARD.code)
        assertEquals(2, DriveCommand.BACKWARD.code)
        assertEquals(3, DriveCommand.STRAFE_LEFT.code)
        assertEquals(4, DriveCommand.STRAFE_RIGHT.code)
        assertEquals(5, DriveCommand.TURN_LEFT.code)
        assertEquals(6, DriveCommand.TURN_RIGHT.code)
    }
}
