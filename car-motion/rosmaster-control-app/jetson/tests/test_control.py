import unittest
from rosmaster_bridge.control import ControlCore, ControlError
from rosmaster_bridge.hardware import MockHardware


class FakeClock:
    def __init__(self): self.value=100.0
    def __call__(self): return self.value
    def advance(self,seconds): self.value+=seconds


class ControlCoreTests(unittest.TestCase):
    def setUp(self):
        self.clock=FakeClock(); self.hardware=MockHardware()
        self.core=ControlCore(self.hardware,700,self.clock,False); self.core.start()
    def tearDown(self): self.core.close()

    def test_start_stops_before_accepting_commands(self):
        self.assertEqual(self.hardware.calls[:2],[("start",),("stop",)]); self.assertTrue(self.core.snapshot().serial_ready)

    def test_continuous_drive_requires_heartbeat(self):
        self.core.drive("a",1,50,0); self.assertTrue(self.core.snapshot().moving)
        self.clock.advance(.6); self.core.heartbeat("a"); self.clock.advance(.6); self.assertFalse(self.core.check_timeouts())
        self.clock.advance(.11); self.assertTrue(self.core.check_timeouts()); self.assertFalse(self.core.snapshot().moving)

    def test_wrong_owner_cannot_renew(self):
        self.core.drive("a",2,30,0)
        with self.assertRaises(ControlError): self.core.heartbeat("b")

    def test_timed_drive_stops_at_deadline(self):
        self.core.drive("a",3,70,500); self.clock.advance(.49); self.assertFalse(self.core.check_timeouts())
        self.clock.advance(.02); self.assertTrue(self.core.check_timeouts()); self.assertIn("时长结束",self.core.snapshot().message)

    def test_new_drive_disables_line_following_first(self):
        self.core.set_follow_line("a",True); before=len(self.hardware.calls); self.core.drive("a",4,100,1000)
        self.assertEqual(self.hardware.calls[before:],[("follow_line",False),("stop",),("drive",4,100)])

    def test_line_following_stops_manual_drive_and_needs_heartbeat(self):
        self.core.drive("a",5,50,0); self.core.set_follow_line("a",True); self.assertTrue(self.core.snapshot().follow_line)
        self.clock.advance(.4); self.core.heartbeat("a"); self.clock.advance(.69); self.assertFalse(self.core.check_timeouts())
        self.clock.advance(.02); self.assertTrue(self.core.check_timeouts()); self.assertFalse(self.core.snapshot().follow_line)

    def test_stop_disables_line_following(self):
        self.core.set_follow_line("a",True); self.core.stop(); self.assertFalse(self.core.snapshot().follow_line)
        self.assertEqual(self.hardware.calls[-2:],[("follow_line",False),("stop",)])

    def test_emergency_stop(self):
        self.core.drive("a",6,100,0); self.core.emergency_stop(); self.assertFalse(self.core.snapshot().moving)
        self.assertEqual(self.core.snapshot().message,"紧急停止已执行")

    def test_owner_disconnect_stops_even_with_observer(self):
        self.core.drive("owner",1,30,0); self.core.client_disconnected("owner",False); self.assertFalse(self.core.snapshot().moving)

    def test_non_owner_disconnect_does_not_interrupt(self):
        self.core.drive("owner",1,30,0); self.core.client_disconnected("observer",False); self.assertTrue(self.core.snapshot().moving)

    def test_last_disconnect_always_stops(self):
        self.core.drive("owner",1,30,0); self.core.client_disconnected("observer",True); self.assertFalse(self.core.snapshot().moving)

    def test_hardware_exception_forces_safe_state(self):
        self.hardware.fail_next="drive"
        with self.assertRaises(ControlError): self.core.drive("a",1,50,0)
        state=self.core.snapshot(); self.assertFalse(state.serial_ready); self.assertFalse(state.moving); self.assertEqual(state.command,7)

    def test_camera_status_is_in_state(self):
        self.core.set_camera_ready(True); self.assertTrue(self.core.snapshot().camera_ready)


if __name__ == "__main__": unittest.main()
