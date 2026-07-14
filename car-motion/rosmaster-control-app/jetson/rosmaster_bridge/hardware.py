"""Real and simulated Rosmaster adapters; vendor imports are runtime-only."""

import contextlib
import importlib
import os
import sys
import threading
from pathlib import Path
from typing import List, Optional, Tuple


class HardwareError(RuntimeError):
    pass


@contextlib.contextmanager
def _working_directory(path: Path):
    previous = Path.cwd()
    os.chdir(str(path))
    try:
        yield
    finally:
        os.chdir(str(previous))


@contextlib.contextmanager
def _silence_output():
    """Suppress license output, including native writes to stdout/stderr."""
    with open(os.devnull, "w", encoding="utf-8") as sink:
        with contextlib.redirect_stdout(sink), contextlib.redirect_stderr(sink):
            saved_stdout = saved_stderr = None
            try:
                saved_stdout = os.dup(1)
                saved_stderr = os.dup(2)
                os.dup2(sink.fileno(), 1)
                os.dup2(sink.fileno(), 2)
            except OSError:
                saved_stdout = saved_stderr = None
            try:
                yield
            finally:
                if saved_stdout is not None and saved_stderr is not None:
                    os.dup2(saved_stdout, 1)
                    os.dup2(saved_stderr, 2)
                    os.close(saved_stdout)
                    os.close(saved_stderr)


class VendorHardware:
    def __init__(self, vendor_root: str, license_app: str = "icar"):
        self.vendor_root = Path(vendor_root).expanduser().resolve()
        self.license_app = license_app
        self._bot = None
        self._lock = threading.RLock()
        self.ready = False
        self._serial_exclusive = False

    def start(self) -> None:
        with self._lock:
            if self.ready:
                return
            if not self.vendor_root.is_dir():
                raise HardwareError("找不到厂商程序目录")
            root_text = str(self.vendor_root)
            sys.path.insert(0, root_text)
            try:
                with _working_directory(self.vendor_root), _silence_output():
                    license_module = importlib.import_module("license")
                    module_file = Path(getattr(license_module, "__file__", "")).resolve()
                    if self.vendor_root not in module_file.parents:
                        raise HardwareError("加载到的不是厂商 license 模块")
                    invalid = bool(license_module.checkLicense(self.license_app))
                if invalid:
                    raise HardwareError("厂商授权校验未通过")
                with _working_directory(self.vendor_root):
                    module = importlib.import_module("Rosmaster_Lib")
                    self._bot = getattr(module, "Rosmaster")(debug=False)
                    self._acquire_serial_exclusive()
                    self._bot.create_receive_threading()
                self.ready = True
            except HardwareError:
                raise
            except Exception as exc:
                raise HardwareError("Rosmaster 串口初始化失败") from exc
            finally:
                try:
                    sys.path.remove(root_text)
                except ValueError:
                    pass

    def drive(self, command: int, speed: int) -> None:
        with self._lock:
            self._require_ready()
            self._bot.set_car_run(command, speed, adjust=False)

    def stop(self) -> None:
        with self._lock:
            if self._bot is not None:
                self._bot.set_car_run(7, 100, adjust=False)

    def set_follow_line(self, enabled: bool) -> None:
        with self._lock:
            self._require_ready()
            self._bot.set_follow_line(1 if enabled else 0)

    def close(self) -> None:
        with self._lock:
            try:
                self.stop()
            finally:
                if self._bot is not None:
                    try:
                        if self._serial_exclusive and os.name != "nt":
                            import fcntl
                            import termios
                            fcntl.ioctl(self._bot.ser.fileno(), termios.TIOCNXCL)
                    except Exception:
                        pass
                    try:
                        self._bot.ser.close()
                    except Exception:
                        pass
                self._serial_exclusive = False
                self.ready = False

    def _require_ready(self) -> None:
        if not self.ready or self._bot is None:
            raise HardwareError("Rosmaster 串口未就绪")

    def _acquire_serial_exclusive(self) -> None:
        if os.name == "nt":
            return
        try:
            import fcntl
            import termios
            fcntl.ioctl(self._bot.ser.fileno(), termios.TIOCEXCL)
            self._serial_exclusive = True
        except Exception as exc:
            try:
                self._bot.ser.close()
            except Exception:
                pass
            raise HardwareError("无法独占 Rosmaster 串口") from exc


class MockHardware:
    def __init__(self):
        self.ready = False
        self.calls: List[Tuple] = []
        self.fail_next: Optional[str] = None
        self._lock = threading.RLock()

    def start(self):
        with self._lock:
            self._maybe_fail("start")
            self.ready = True
            self.calls.append(("start",))

    def drive(self, command, speed):
        with self._lock:
            self._maybe_fail("drive")
            self.calls.append(("drive", command, speed))

    def stop(self):
        with self._lock:
            self.calls.append(("stop",))

    def set_follow_line(self, enabled):
        with self._lock:
            self._maybe_fail("follow_line")
            self.calls.append(("follow_line", bool(enabled)))

    def close(self):
        with self._lock:
            self.calls.append(("close",))
            self.ready = False

    def _maybe_fail(self, operation):
        if self.fail_next == operation:
            self.fail_next = None
            raise HardwareError("模拟硬件异常")
