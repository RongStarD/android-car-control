"""Validation and serialization for protocol version 1."""

import json
from dataclasses import dataclass
from typing import Any, Dict, Optional, Union

PROTOCOL_VERSION = 1
DRIVE_COMMANDS = frozenset(range(1, 7))
SPEEDS = frozenset((30, 50, 70, 100))
DURATIONS_MS = frozenset((0, 500, 1000))
OPS = frozenset(("drive", "heartbeat", "stop", "follow_line", "emergency_stop"))
RequestId = Union[str, int]


class ProtocolError(ValueError):
    def __init__(self, message: str, request_id: Optional[RequestId] = None):
        super().__init__(message)
        self.request_id = request_id


@dataclass(frozen=True)
class Request:
    op: str
    request_id: RequestId
    command: Optional[int] = None
    speed: Optional[int] = None
    duration_ms: Optional[int] = None
    enabled: Optional[bool] = None


def _valid_request_id(value: Any) -> bool:
    return (isinstance(value, str) and 0 < len(value) <= 128) or (
        isinstance(value, int) and not isinstance(value, bool)
    )


def _strict_int(value: Any, allowed: frozenset) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value in allowed


def parse_request(raw: Union[str, bytes]) -> Request:
    try:
        data = json.loads(raw)
    except (TypeError, ValueError, UnicodeDecodeError) as exc:
        raise ProtocolError("请求不是有效的 JSON") from exc
    if not isinstance(data, dict):
        raise ProtocolError("请求必须是 JSON 对象")
    request_id = data.get("id")
    if not _valid_request_id(request_id):
        raise ProtocolError("id 必须是非空字符串或整数")
    op = data.get("op")
    if op not in OPS:
        raise ProtocolError("不支持的 op", request_id)
    if op == "drive":
        if not _strict_int(data.get("command"), DRIVE_COMMANDS):
            raise ProtocolError("command 必须是 1 到 6 的整数", request_id)
        if not _strict_int(data.get("speed"), SPEEDS):
            raise ProtocolError("speed 只能是 30、50、70 或 100", request_id)
        if not _strict_int(data.get("duration_ms"), DURATIONS_MS):
            raise ProtocolError("duration_ms 只能是 0、500 或 1000", request_id)
        return Request(op, request_id, data["command"], data["speed"], data["duration_ms"])
    if op == "follow_line":
        enabled = data.get("enabled")
        if not isinstance(enabled, bool):
            raise ProtocolError("enabled 必须是布尔值", request_id)
        return Request(op=op, request_id=request_id, enabled=enabled)
    return Request(op=op, request_id=request_id)


def response(request_id: Optional[RequestId], ok: bool, message: str) -> Dict[str, Any]:
    return {"op": "response", "id": request_id, "ok": bool(ok), "message": str(message)}


def hello(state: Dict[str, Any]) -> Dict[str, Any]:
    return {"op": "hello", "protocol": PROTOCOL_VERSION, "state": state}
