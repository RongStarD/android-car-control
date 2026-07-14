"""Thread-safe command arbiter and safety watchdog."""

import threading
import time
from dataclasses import asdict, dataclass
from typing import Callable, Dict, List, Optional


class ControlError(RuntimeError):
    pass


@dataclass(frozen=True)
class ControlState:
    serial_ready: bool = False
    camera_ready: bool = False
    moving: bool = False
    command: int = 7
    speed: int = 50
    duration_ms: int = 0
    follow_line: bool = False
    message: str = "正在启动"

    def to_dict(self) -> Dict[str, object]:
        return asdict(self)


class ControlCore:
    def __init__(self, hardware, watchdog_ms=700, clock=time.monotonic, start_watchdog=True):
        if watchdog_ms < 100:
            raise ValueError("watchdog_ms must be at least 100")
        self._hardware = hardware
        self._watchdog_seconds = watchdog_ms / 1000.0
        self._clock = clock
        self._lock = threading.RLock()
        self._condition = threading.Condition(self._lock)
        self._listeners: List[Callable[[ControlState], None]] = []
        self._state = ControlState()
        self._owner: Optional[str] = None
        self._deadline: Optional[float] = None
        self._deadline_reason: Optional[str] = None
        self._closed = False
        self._watchdog_thread = None
        if start_watchdog:
            self._watchdog_thread = threading.Thread(target=self._watchdog_loop, name="motion-watchdog", daemon=True)
            self._watchdog_thread.start()

    def add_listener(self, listener):
        with self._lock:
            self._listeners.append(listener)

    def snapshot(self):
        with self._lock:
            return self._state

    def start(self):
        try:
            self._hardware.start()
            self._hardware.stop()
        except Exception as exc:
            self._hardware_stop_best_effort()
            self._set_state(serial_ready=False, message="底盘初始化失败，小车已停止")
            raise ControlError("底盘初始化失败") from exc
        self._set_state(serial_ready=True, message="底盘已就绪")

    def set_camera_ready(self, ready, message=None):
        changes = {"camera_ready": bool(ready)}
        if message:
            changes["message"] = message
        self._set_state(**changes)

    def drive(self, owner, command, speed, duration_ms):
        failure = None
        with self._lock:
            self._require_ready_locked()
            try:
                if self._state.follow_line:
                    self._hardware.set_follow_line(False)
                self._hardware.stop()
                self._hardware.drive(command, speed)
            except Exception as exc:
                self._fail_safe_locked("下发运动命令失败")
                failure = exc
                message = "下发运动命令失败"
            else:
                self._owner = owner
                if duration_ms == 0:
                    self._deadline = self._clock() + self._watchdog_seconds
                    self._deadline_reason = "watchdog"
                    message = "持续运动中，请保持心跳"
                else:
                    self._deadline = self._clock() + duration_ms / 1000.0
                    self._deadline_reason = "duration"
                    message = "定时运动中"
                self._state = ControlState(True, self._state.camera_ready, True, command, speed, duration_ms, False, message)
                self._condition.notify_all()
            state = self._state
        self._notify(state)
        if failure is not None:
            raise ControlError(message) from failure
        return message

    def heartbeat(self, owner):
        with self._lock:
            renewable = (self._state.moving and self._state.duration_ms == 0) or self._state.follow_line
            if not renewable:
                raise ControlError("当前没有需要续租的持续任务")
            if self._owner != owner:
                raise ControlError("当前连接不是运动任务的控制者")
            self._deadline = self._clock() + self._watchdog_seconds
            self._deadline_reason = "watchdog"
            self._condition.notify_all()
        return "心跳已续租"

    def stop(self, message="小车已停止"):
        failure = None
        with self._lock:
            try:
                self._safe_stop_locked(message, disable_follow=True)
            except ControlError as exc:
                failure = exc
            state = self._state
        self._notify(state)
        if failure is not None:
            raise failure
        return message

    def emergency_stop(self):
        return self.stop("紧急停止已执行")

    def set_follow_line(self, owner, enabled):
        failure = None
        with self._lock:
            self._require_ready_locked()
            try:
                self._hardware.stop()
                self._hardware.set_follow_line(enabled)
            except Exception as exc:
                self._fail_safe_locked("切换寻迹失败")
                failure = exc
            else:
                self._owner = owner if enabled else None
                self._deadline = self._clock() + self._watchdog_seconds if enabled else None
                self._deadline_reason = "watchdog" if enabled else None
                message = "寻迹已开启，请保持心跳" if enabled else "寻迹已关闭，小车已停止"
                self._state = ControlState(True, self._state.camera_ready, False, 7, self._state.speed, 0, enabled, message)
                self._condition.notify_all()
            state = self._state
        self._notify(state)
        if failure is not None:
            raise ControlError("切换寻迹失败") from failure
        return state.message

    def client_disconnected(self, owner, last_client):
        with self._lock:
            should_stop = last_client or owner == self._owner
        if should_stop:
            self.stop("控制连接已断开，小车已停止")

    def check_timeouts(self):
        with self._lock:
            if self._deadline is None or self._clock() < self._deadline:
                return False
            message = "运动时长结束，小车已停止" if self._deadline_reason == "duration" else "控制心跳超时，小车已停止"
            self._safe_stop_locked(message, disable_follow=True)
            state = self._state
        self._notify(state)
        return True

    def close(self):
        try:
            self.stop("服务正在关闭，小车已停止")
        finally:
            with self._lock:
                self._closed = True
                self._condition.notify_all()
            try:
                self._hardware.close()
            finally:
                self._set_state(serial_ready=False, message="服务已关闭")

    def _watchdog_loop(self):
        while True:
            with self._condition:
                if self._closed:
                    return
                if self._deadline is None:
                    self._condition.wait(timeout=0.25)
                    continue
                remaining = self._deadline - self._clock()
                if remaining > 0:
                    self._condition.wait(timeout=min(remaining, 0.25))
                    continue
            self.check_timeouts()

    def _require_ready_locked(self):
        if not self._state.serial_ready:
            raise ControlError("底盘串口尚未就绪")

    def _safe_stop_locked(self, message, disable_follow):
        try:
            if disable_follow and self._state.follow_line:
                self._hardware.set_follow_line(False)
            self._hardware.stop()
        except Exception as exc:
            self._fail_safe_locked("停止命令执行异常")
            raise ControlError("停止命令执行异常") from exc
        self._owner = None
        self._deadline = None
        self._deadline_reason = None
        self._state = ControlState(self._state.serial_ready, self._state.camera_ready, False, 7, self._state.speed, 0, False, message)
        self._condition.notify_all()

    def _fail_safe_locked(self, message):
        self._hardware_stop_best_effort()
        self._owner = None
        self._deadline = None
        self._deadline_reason = None
        self._state = ControlState(False, self._state.camera_ready, False, 7, self._state.speed, 0, False, message + "，小车已停止")
        self._condition.notify_all()

    def _hardware_stop_best_effort(self):
        try:
            self._hardware.set_follow_line(False)
        except Exception:
            pass
        try:
            self._hardware.stop()
        except Exception:
            pass

    def _set_state(self, **changes):
        with self._lock:
            values = self._state.to_dict()
            values.update(changes)
            self._state = ControlState(**values)
            state = self._state
        self._notify(state)

    def _notify(self, state):
        with self._lock:
            listeners = tuple(self._listeners)
        for listener in listeners:
            try:
                listener(state)
            except Exception:
                continue
