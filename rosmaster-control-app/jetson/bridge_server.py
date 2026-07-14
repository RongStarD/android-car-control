#!/usr/bin/env python3
"""Standalone Jetson service for the Android Rosmaster controller."""

import argparse
import asyncio
import os
import signal
import subprocess
import sys
from pathlib import Path

from rosmaster_bridge.control import ControlCore, ControlError
from rosmaster_bridge.hardware import MockHardware, VendorHardware
from rosmaster_bridge.instance_lock import AlreadyRunningError, SingleInstanceLock
from rosmaster_bridge.video import CameraCapture, DisabledCamera, MjpegServer
from rosmaster_bridge.ws_server import WebSocketControlServer


def parse_device(value):
    return int(value) if value.isdigit() else value


def build_parser():
    parser = argparse.ArgumentParser(description="Rosmaster Android control bridge")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--ws-port", type=int, default=9093)
    parser.add_argument("--video-port", type=int, default=9094)
    parser.add_argument("--camera-device", default="0")
    parser.add_argument("--camera-width", type=int, default=640)
    parser.add_argument("--camera-height", type=int, default=480)
    parser.add_argument("--jpeg-quality", type=int, default=75)
    parser.add_argument("--watchdog-ms", type=int, default=700)
    parser.add_argument("--vendor-root", default="/home/jetson/Rosmaster-App/rosmaster")
    parser.add_argument("--license-app", default="icar")
    parser.add_argument("--lock-file", default="")
    parser.add_argument("--mock", action="store_true", help="do not open serial or check a license")
    parser.add_argument("--no-camera", action="store_true")
    return parser


def conflicting_vendor_processes():
    if os.name == "nt" or not Path("/proc").is_dir(): return []
    protected = {
        "app.py",
        "app_sim_run.py",
        "rosmaster_test.py",
        "Mcnamu_driver_X3",
        "yahboomcar_driver",
    }
    conflicts = []
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid(): continue
        try:
            args = (entry / "cmdline").read_bytes().split(b"\0")
            names = {Path(v.decode("utf-8", "ignore")).name for v in args if v}
            if names & protected: conflicts.append(int(entry.name))
        except (OSError, ValueError):
            continue
    return conflicts


def serial_device_in_use(device="/dev/myserial"):
    if os.name == "nt": return False
    path = Path(device)
    if not path.exists(): return False
    targets = {str(path), str(path.resolve())}
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit() or int(entry.name) == os.getpid(): continue
        try:
            for fd in (entry / "fd").iterdir():
                try:
                    if os.path.realpath(str(fd)) in targets: return True
                except OSError: pass
        except OSError:
            continue
    try:
        for target in targets:
            result = subprocess.run(["fuser", "-s", target], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2, check=False)
            if result.returncode == 0: return True
    except (FileNotFoundError, subprocess.SubprocessError):
        pass
    return False


async def run(args):
    lock = SingleInstanceLock(args.lock_file)
    try:
        lock.acquire()
    except AlreadyRunningError as exc:
        print(str(exc), file=sys.stderr); return 2
    if not args.mock:
        if conflicting_vendor_processes():
            print("检测到厂商桌面程序或底盘驱动正在运行；请先人工关闭，控制桥不会抢占", file=sys.stderr)
            lock.release(); return 2
        if serial_device_in_use():
            print("检测到 /dev/myserial 已被占用；请先人工停止占用程序，控制桥不会抢占", file=sys.stderr)
            lock.release(); return 2

    hardware = MockHardware() if args.mock else VendorHardware(args.vendor_root, args.license_app)
    core = ControlCore(hardware, watchdog_ms=args.watchdog_ms)
    camera = DisabledCamera() if args.no_camera or args.mock else CameraCapture(
        parse_device(args.camera_device), args.camera_width, args.camera_height, args.jpeg_quality,
        on_ready=lambda ready: core.set_camera_ready(ready))
    video_server = MjpegServer(args.host, args.video_port, camera)
    ws_server = WebSocketControlServer(args.host, args.ws_port, core)
    shutdown = asyncio.Event()
    loop = asyncio.get_running_loop()
    def request_shutdown(): shutdown.set()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try: loop.add_signal_handler(sig, request_shutdown)
        except (NotImplementedError, RuntimeError):
            signal.signal(sig, lambda *_: loop.call_soon_threadsafe(request_shutdown))
    try:
        await loop.run_in_executor(None, core.start)
        camera.start(); video_server.start(); await ws_server.start()
        print("控制桥已启动：WebSocket {}，MJPEG {}".format(args.ws_port, args.video_port), flush=True)
        await shutdown.wait()
        return 0
    except (ControlError, OSError, RuntimeError) as exc:
        print("启动失败：{}".format(exc), file=sys.stderr, flush=True); return 1
    except Exception as exc:
        print("服务异常，小车已停止：{}".format(type(exc).__name__), file=sys.stderr, flush=True); return 1
    finally:
        try: await ws_server.close()
        except Exception: pass
        try: video_server.close()
        except Exception: pass
        try: camera.close()
        except Exception: pass
        try: await loop.run_in_executor(None, core.close)
        except Exception: pass
        lock.release()


def main():
    args = build_parser().parse_args()
    if not (1 <= args.ws_port <= 65535 and 1 <= args.video_port <= 65535):
        print("端口必须在 1 到 65535 之间", file=sys.stderr); return 2
    if args.ws_port == args.video_port:
        print("WebSocket 与视频端口不能相同", file=sys.stderr); return 2
    if not (1 <= args.jpeg_quality <= 100):
        print("JPEG 质量必须在 1 到 100 之间", file=sys.stderr); return 2
    try: return asyncio.run(run(args))
    except KeyboardInterrupt: return 0


if __name__ == "__main__":
    raise SystemExit(main())
