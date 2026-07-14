#!/usr/bin/env python3
"""Local acceptance server for Android UI/video tests; it never controls hardware."""

import argparse
import asyncio
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import websockets


def start_video_server(host, port, frame):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.split("?", 1)[0] == "/healthz":
                body = b'{"camera_ready":true}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if self.path.split("?", 1)[0] != "/video.mjpg":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                while True:
                    self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\n")
                    self.wfile.write(("Content-Length: %d\r\n\r\n" % len(frame)).encode("ascii"))
                    self.wfile.write(frame)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()
                    time.sleep(0.1)
            except (BrokenPipeError, ConnectionResetError, OSError):
                return

        def log_message(self, *_):
            return

    server = ThreadingHTTPServer((host, port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


async def control_handler(websocket, _path=None):
    state = {
        "serial_ready": True,
        "camera_ready": True,
        "moving": False,
        "command": 7,
        "speed": 50,
        "duration_ms": 0,
        "follow_line": False,
        "message": "本地视频验收模式",
    }
    await websocket.send(json.dumps({"op": "hello", "protocol": 1, "state": state}))
    await websocket.send(json.dumps({"op": "state", **state}))
    async for raw in websocket:
        request = json.loads(raw)
        request_id = request.get("id")
        if request.get("op") == "drive" or (
            request.get("op") == "follow_line" and request.get("enabled") is True
        ):
            response = {"op": "response", "id": request_id, "ok": False, "message": "验收服务禁止运动"}
        else:
            response = {"op": "response", "id": request_id, "ok": True, "message": "小车已停止"}
        await websocket.send(json.dumps(response, ensure_ascii=False))


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("frame", type=Path)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--ws-port", type=int, default=19093)
    parser.add_argument("--video-port", type=int, default=19094)
    args = parser.parse_args()
    frame = args.frame.read_bytes()
    if not (frame.startswith(b"\xff\xd8") and frame.endswith(b"\xff\xd9")):
        raise SystemExit("frame must be a JPEG file")
    video_server = start_video_server(args.host, args.video_port, frame)
    try:
        async with websockets.serve(control_handler, args.host, args.ws_port):
            print("Mock acceptance server ready", flush=True)
            await asyncio.Future()
    finally:
        video_server.shutdown()
        video_server.server_close()


if __name__ == "__main__":
    asyncio.run(main())
