"""Async WebSocket transport for protocol v1."""

import asyncio
import json
import secrets

from .control import ControlError
from .protocol import ProtocolError, hello, parse_request, response


class WebSocketControlServer:
    def __init__(self, host, port, core):
        self.host, self.port, self.core = host, port, core
        self._clients = set()
        self._loop = self._server = None
        self._closed = False
        core.add_listener(self._on_state)

    async def start(self):
        try:
            import websockets
        except ImportError as exc:
            raise RuntimeError("缺少 websockets，请先运行安装脚本") from exc
        self._loop = asyncio.get_running_loop()
        self._server = await websockets.serve(self._handler, self.host, self.port, ping_interval=20, ping_timeout=20, max_size=65536)

    async def close(self):
        self._closed = True
        if self._server:
            self._server.close(); await self._server.wait_closed()

    async def _handler(self, websocket, path=None):
        owner = secrets.token_hex(12)
        self._clients.add(websocket)
        initial = self.core.snapshot().to_dict()
        if initial["serial_ready"] and not initial["moving"] and not initial["follow_line"]:
            initial["message"] = "底盘已就绪"
        await self._send(websocket, hello(initial))
        await self._send(websocket, {"op": "state", **initial})
        try:
            async for raw in websocket:
                await self._handle_request(websocket, owner, raw)
        except Exception:
            pass
        finally:
            self._clients.discard(websocket)
            await asyncio.get_running_loop().run_in_executor(None, self.core.client_disconnected, owner, not self._clients)

    async def _handle_request(self, websocket, owner, raw):
        try:
            request = parse_request(raw)
        except ProtocolError as exc:
            await self._send(websocket, response(exc.request_id, False, str(exc))); return
        loop = asyncio.get_running_loop()
        try:
            if request.op == "drive":
                message = await loop.run_in_executor(None, self.core.drive, owner, request.command, request.speed, request.duration_ms)
            elif request.op == "heartbeat":
                message = await loop.run_in_executor(None, self.core.heartbeat, owner)
            elif request.op == "stop":
                message = await loop.run_in_executor(None, self.core.stop)
            elif request.op == "follow_line":
                message = await loop.run_in_executor(None, self.core.set_follow_line, owner, request.enabled)
            elif request.op == "emergency_stop":
                message = await loop.run_in_executor(None, self.core.emergency_stop)
            else:
                raise ControlError("不支持的命令")
        except ControlError as exc:
            await self._send(websocket, response(request.request_id, False, str(exc))); return
        except Exception:
            try: await loop.run_in_executor(None, self.core.stop, "服务异常，小车已停止")
            except Exception: pass
            await self._send(websocket, response(request.request_id, False, "服务异常，小车已停止")); return
        await self._send(websocket, response(request.request_id, True, message))

    def _on_state(self, state):
        if self._loop is None or self._loop.is_closed() or self._closed: return
        asyncio.run_coroutine_threadsafe(self._broadcast({"op": "state", **state.to_dict()}), self._loop)

    async def _broadcast(self, message):
        if self._clients:
            await asyncio.gather(*(self._send(c, message) for c in tuple(self._clients)), return_exceptions=True)

    @staticmethod
    async def _send(websocket, message):
        await websocket.send(json.dumps(message, ensure_ascii=False, separators=(",", ":")))
