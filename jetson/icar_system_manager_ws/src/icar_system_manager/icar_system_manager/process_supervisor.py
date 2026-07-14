import os
from pathlib import Path
import signal
import subprocess
import time
from typing import Dict, Mapping, Optional

from .models import GroupSpec


class ManagedProcess:
    """Own one launch process and its process group."""

    def __init__(self, spec: GroupSpec, log_dir: str, environment: Mapping[str, str]):
        self.spec = spec
        self.log_dir = Path(log_dir)
        self.environment = dict(environment)
        self.process: Optional[subprocess.Popen] = None
        self.started_at: Optional[float] = None
        self.log_path: Optional[Path] = None
        self._log_stream = None

    def start(self) -> None:
        if self.is_running():
            raise RuntimeError("group {} is already running".format(self.spec.name))
        self.log_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.log_path = self.log_dir / "{}-{}.log".format(self.spec.name, stamp)
        self._log_stream = self.log_path.open("ab", buffering=0)
        env: Dict[str, str] = dict(os.environ)
        env.update(self.environment)
        try:
            self.process = subprocess.Popen(
                self.spec.command,
                stdin=subprocess.DEVNULL,
                stdout=self._log_stream,
                stderr=subprocess.STDOUT,
                env=env,
                start_new_session=(os.name != "nt"),
            )
        except Exception:
            self._close_log()
            raise
        self.started_at = time.monotonic()

    def is_running(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def poll(self) -> Optional[int]:
        if self.process is None:
            return None
        code = self.process.poll()
        if code is not None:
            self._close_log()
        return code

    def elapsed(self) -> float:
        if self.started_at is None:
            return 0.0
        return time.monotonic() - self.started_at

    def stop(self) -> None:
        process = self.process
        if process is None:
            return
        if process.poll() is not None:
            self._close_log()
            return

        self._signal(signal.SIGINT)
        try:
            process.wait(timeout=self.spec.shutdown_timeout_sec)
        except subprocess.TimeoutExpired:
            self._signal(signal.SIGTERM)
            try:
                process.wait(timeout=3.0)
            except subprocess.TimeoutExpired:
                self._signal(signal.SIGKILL)
                process.wait(timeout=2.0)
        finally:
            self._close_log()

    def _signal(self, sig: signal.Signals) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        if os.name == "nt":
            if sig == signal.SIGINT:
                self.process.send_signal(signal.CTRL_BREAK_EVENT)
            else:
                self.process.send_signal(sig)
            return
        try:
            os.killpg(os.getpgid(self.process.pid), sig)
        except ProcessLookupError:
            pass

    def _close_log(self) -> None:
        if self._log_stream is not None:
            self._log_stream.close()
            self._log_stream = None


class ProcessSupervisor:
    def __init__(self, log_dir: str, environment: Mapping[str, str]):
        self.log_dir = log_dir
        self.environment = environment
        self.processes: Dict[str, ManagedProcess] = {}

    def start(self, spec: GroupSpec) -> ManagedProcess:
        existing = self.processes.get(spec.name)
        if existing is not None and existing.is_running():
            return existing
        process = ManagedProcess(spec, self.log_dir, self.environment)
        process.start()
        self.processes[spec.name] = process
        return process

    def get(self, group_name: str) -> Optional[ManagedProcess]:
        return self.processes.get(group_name)

    def stop(self, group_name: str) -> None:
        process = self.processes.get(group_name)
        if process is not None:
            process.stop()

    def stop_all(self, ordered_groups) -> None:
        for group_name in reversed(list(ordered_groups)):
            self.stop(group_name)

    def running_groups(self):
        return [name for name, process in self.processes.items() if process.is_running()]
