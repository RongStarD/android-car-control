"""Optional OpenCV capture and standard-library MJPEG HTTP endpoint."""

import json
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class CameraCapture:
    def __init__(self, device=0, width=640, height=480, quality=75, on_ready=None):
        self.device, self.width, self.height, self.quality = device, width, height, quality
        self.on_ready = on_ready
        self._condition = threading.Condition()
        self._frame = None
        self._sequence = 0
        self._ready = False
        self._closed = False
        self._thread = None

    @property
    def ready(self):
        with self._condition:
            return self._ready

    def start(self):
        if self._thread is None:
            self._thread = threading.Thread(target=self._capture_loop, name="camera-capture", daemon=True)
            self._thread.start()

    def close(self):
        with self._condition:
            self._closed = True
            self._condition.notify_all()
        if self._thread is not None:
            self._thread.join(timeout=2)

    def wait_for_frame(self, after_sequence, timeout=2.0):
        with self._condition:
            if self._sequence <= after_sequence and not self._closed:
                self._condition.wait_for(lambda: self._sequence > after_sequence or self._closed, timeout=timeout)
            return self._sequence, self._frame

    def _set_ready(self, ready):
        callback = None
        with self._condition:
            if ready != self._ready:
                self._ready = ready
                if not ready:
                    self._frame = None
                    self._condition.notify_all()
                callback = self.on_ready
        if callback:
            try:
                callback(ready)
            except Exception:
                pass

    def _capture_loop(self):
        capture = None
        try:
            try:
                import cv2
            except ImportError:
                self._set_ready(False)
                return
            while True:
                with self._condition:
                    if self._closed:
                        return
                if capture is None or not capture.isOpened():
                    capture = cv2.VideoCapture(self.device)
                    if not capture.isOpened():
                        self._set_ready(False)
                        capture.release()
                        capture = None
                        time.sleep(1)
                        continue
                    capture.set(cv2.CAP_PROP_FRAME_WIDTH, self.width)
                    capture.set(cv2.CAP_PROP_FRAME_HEIGHT, self.height)
                ok, frame = capture.read()
                if not ok:
                    self._set_ready(False)
                    capture.release()
                    capture = None
                    time.sleep(0.5)
                    continue
                frame = cv2.resize(frame, (self.width, self.height))
                ok, encoded = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), self.quality])
                if not ok:
                    self._set_ready(False)
                    continue
                with self._condition:
                    self._frame = encoded.tobytes()
                    self._sequence += 1
                    self._condition.notify_all()
                self._set_ready(True)
        finally:
            if capture is not None:
                capture.release()
            self._set_ready(False)


class DisabledCamera:
    ready = False
    def start(self): pass
    def close(self): pass
    def wait_for_frame(self, after_sequence, timeout=2.0):
        time.sleep(min(timeout, 0.05))
        return after_sequence, None


class MjpegServer:
    def __init__(self, host, port, camera):
        self.host, self.port, self.camera = host, port, camera
        self._httpd = self._thread = None

    def start(self):
        camera = self.camera
        class Handler(BaseHTTPRequestHandler):
            server_version = "RosmasterMjpeg/1"
            def do_GET(self):
                path = self.path.split("?", 1)[0]
                if path == "/healthz":
                    payload = json.dumps({"camera_ready": bool(camera.ready)}).encode()
                    self.send_response(HTTPStatus.OK)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Content-Length", str(len(payload)))
                    self.send_header("Cache-Control", "no-store")
                    self.end_headers(); self.wfile.write(payload); return
                if path != "/video.mjpg":
                    self.send_error(HTTPStatus.NOT_FOUND); return
                if not camera.ready:
                    self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, "camera is not ready"); return
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                sequence = -1
                try:
                    while True:
                        sequence, frame = camera.wait_for_frame(sequence, 2)
                        if frame is None:
                            if not camera.ready: return
                            continue
                        self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\n")
                        self.wfile.write(("Content-Length: %d\r\n\r\n" % len(frame)).encode("ascii"))
                        self.wfile.write(frame); self.wfile.write(b"\r\n")
                except (BrokenPipeError, ConnectionResetError, OSError):
                    return
            def log_message(self, fmt, *args):
                return
        self._httpd = ThreadingHTTPServer((self.host, self.port), Handler)
        self._httpd.daemon_threads = True
        self._thread = threading.Thread(target=self._httpd.serve_forever, name="mjpeg-http", daemon=True)
        self._thread.start()

    def close(self):
        if self._httpd:
            self._httpd.shutdown(); self._httpd.server_close()
        if self._thread:
            self._thread.join(timeout=2)
