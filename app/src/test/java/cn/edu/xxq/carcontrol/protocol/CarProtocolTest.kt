package cn.edu.xxq.carcontrol.protocol

import cn.edu.xxq.carcontrol.model.DriveDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class CarProtocolTest {
    @Test
    fun `forward matches the course reference frame`() {
        assertEquals("\$011504011B#", CarProtocol.button(DriveDirection.Forward))
    }

    @Test
    fun `stop matches the course reference frame`() {
        assertEquals("\$011504001A#", CarProtocol.button(DriveDirection.Stop))
    }

    @Test
    fun `negative joystick values use two's complement bytes`() {
        assertEquals("\$0110069C32E5#", CarProtocol.joystick(-100, 50))
    }
}
