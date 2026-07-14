#!/usr/bin/env python3
"""Local-only AppBridge simulator for Android UI and protocol smoke tests."""

import argparse
import asyncio
import json
import math
import struct
import zlib

import websockets


MAGIC = b"ICAR"


def grid_packet(packet_type, width, height, resolution, origin_x, origin_y, cells):
    raw = bytes((value + 256) % 256 for value in cells)
    compressed = zlib.compress(raw, level=4)
    return struct.pack(
        "!4sBIIfffII",
        MAGIC,
        packet_type,
        width,
        height,
        resolution,
        origin_x,
        origin_y,
        len(raw),
        len(compressed),
    ) + compressed


def path_packet(packet_type, points):
    payload = bytearray(struct.pack("!4sBH", MAGIC, packet_type, len(points)))
    for point in points:
        payload.extend(struct.pack("!fff", *point))
    return bytes(payload)


def sample_map():
    width, height = 80, 60
    cells = [0] * (width * height)
    for y in range(height):
        for x in range(width):
            if x in (0, width - 1) or y in (0, height - 1):
                cells[y * width + x] = 100
            elif 18 <= x <= 21 and 8 <= y <= 42:
                cells[y * width + x] = 100
            elif 45 <= x <= 68 and 28 <= y <= 31:
                cells[y * width + x] = 100
    return grid_packet(1, width, height, 0.05, -2.0, -1.5, cells)


def sample_costmap(packet_type, local=False):
    width, height = (32, 32) if local else (80, 60)
    resolution = 0.05
    origin_x, origin_y = (-0.8, -0.8) if local else (-2.0, -1.5)
    cells = [0] * (width * height)
    center_x, center_y = width // 2, height // 2
    for y in range(height):
        for x in range(width):
            distance = math.hypot(x - center_x, y - center_y)
            if 7.0 <= distance <= 10.0:
                cells[y * width + x] = max(1, int(100 - abs(distance - 8.5) * 40))
    return grid_packet(packet_type, width, height, resolution, origin_x, origin_y, cells)


MAP_PACKET = sample_map()
LOCAL_COSTMAP = sample_costmap(4, local=True)
GLOBAL_COSTMAP = sample_costmap(5, local=False)
GLOBAL_PATH = path_packet(6, [(-1.2 + index * 0.14, -0.7 + index * 0.07, 0.45) for index in range(22)])
LOCAL_PATH = path_packet(7, [(0.0 + index * 0.07, 0.0 + index * 0.04, 0.35) for index in range(10)])
POSE_PACKET = struct.pack("!4sBfff", MAGIC, 2, 0.0, 0.0, 0.35)
SCAN_PACKET = struct.pack("!4sBHff", MAGIC, 3, 36, -math.pi, math.pi * 2 / 36) + struct.pack(
    "!36f", *([0.75] * 36)
)


async def send_json(socket, payload):
    await socket.send(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


async def runtime(socket, mode, phase, ready, detail):
    await send_json(
        socket,
        {
            "op": "runtime",
            "mode": mode,
            "phase": phase,
            "ready": ready,
            "detail": detail,
            "error": "",
        },
    )


async def send_goal_state(socket, sequence, state, result_status=None):
    running = state in {"pending", "active", "cancel_pending"}
    await send_json(
        socket,
        {
            "op": "state",
            "navigation_active": running,
            "navigation_goal_sequence": sequence,
            "navigation_goal_state": state,
            "navigation_result_status": result_status,
            # Keep the nested form too so the simulator exercises both forms
            # accepted by the Android compatibility decoder.
            "navigation_goal": {
                "sequence": sequence,
                "state": state,
                "result_status": result_status,
            },
        },
    )


async def finish_goal(socket, sequence):
    # Deliberately report the WebSocket response before this first state to
    # exercise the real goal-publish/Action-acceptance race.
    await asyncio.sleep(0.15)
    await send_goal_state(socket, sequence, "pending")
    await asyncio.sleep(0.35)
    await send_goal_state(socket, sequence, "active")
    await send_json(socket, {"op": "state", "navigation_status": "导航中，剩余距离 1.25 m"})
    await asyncio.sleep(1.0)
    await send_goal_state(socket, sequence, "succeeded", 4)
    await send_json(socket, {"op": "state", "navigation_status": "导航目标已完成"})


async def confirm_cancel(socket, sequence):
    await send_goal_state(socket, sequence, "cancel_pending")
    await asyncio.sleep(0.5)
    await send_goal_state(socket, sequence, "canceled", 5)
    await send_json(socket, {"op": "state", "navigation_status": "导航目标已取消"})


async def handler(socket):
    await send_json(socket, {"op": "hello", "protocol": 1})
    goal_sequence = 0
    goal_task = None
    await send_json(
        socket,
        {
            "op": "state",
            "mapping_active": False,
            "navigation_ready": False,
            "navigation_active": False,
            "navigation_status": "未启动",
            "navigation_goal_sequence": 0,
            "navigation_goal_state": "none",
            "navigation_result_status": None,
            "navigation_goal": {"sequence": 0, "state": "none", "result_status": None},
            "local_overlay_frame": "odom",
            "local_plan_frame": "map",
        },
    )
    await runtime(socket, "idle", "idle", False, "本地测试桥接已连接")
    await socket.send(MAP_PACKET)

    async for raw_message in socket:
        if not isinstance(raw_message, str):
            continue
        message = json.loads(raw_message)
        request_id = message.get("id", "")
        command = message.get("command", "")
        response_text = "测试命令已受理"

        if command == "load_saved_map":
            response_text = "已读取测试地图"
            await socket.send(MAP_PACKET)
        elif command == "start_navigation_base":
            response_text = "已启动测试 n1"
            await runtime(socket, "navigation_base", "ready", True, "n1 已就绪：底盘、雷达与 TF 可用")
        elif command in ("start_navigation_dwa", "start_navigation_teb"):
            response_text = "已启动测试 Nav2"
            await runtime(
                socket,
                "navigation",
                "awaiting_initial_pose",
                False,
                "Nav2 核心节点已启动，请设置初始位姿",
            )
        elif command == "initial_pose":
            response_text = "初始位姿已发布"
            await send_json(socket, {"op": "state", "navigation_ready": True})
            await runtime(socket, "navigation", "ready", True, "Nav2 已就绪，可以发送目标")
            for packet in (GLOBAL_COSTMAP, LOCAL_COSTMAP, GLOBAL_PATH, LOCAL_PATH, POSE_PACKET, SCAN_PACKET):
                await socket.send(packet)
        elif command == "goal_pose":
            response_text = "导航目标已发布"
            goal_sequence += 1
            if goal_task is not None:
                goal_task.cancel()
            goal_task = asyncio.create_task(finish_goal(socket, goal_sequence))
        elif command == "cancel_navigation":
            response_text = "已请求取消测试导航"
            await send_json(socket, {"op": "state", "navigation_status": "已请求取消导航"})
            if goal_task is not None:
                goal_task.cancel()
            goal_task = asyncio.create_task(confirm_cancel(socket, goal_sequence))
        elif command == "stop_navigation":
            response_text = "已结束测试导航环境"
            if goal_task is not None:
                goal_task.cancel()
                goal_task = None
            await send_json(
                socket,
                {
                    "op": "state",
                    "navigation_ready": False,
                    "navigation_active": False,
                    "navigation_status": "未启动",
                    "navigation_goal_sequence": goal_sequence,
                    "navigation_goal_state": "none",
                    "navigation_result_status": None,
                    "navigation_goal": {
                        "sequence": goal_sequence,
                        "state": "none",
                        "result_status": None,
                    },
                },
            )
            await runtime(socket, "idle", "idle", False, "导航环境已停止")
        elif command == "emergency_stop":
            response_text = "测试急停指令已接收"
            if goal_task is not None:
                goal_task.cancel()
                goal_task = asyncio.create_task(confirm_cancel(socket, goal_sequence))
        elif command == "twist":
            response_text = "测试零速/运动指令已接收"

        await send_json(
            socket,
            {"op": "response", "id": request_id, "ok": True, "message": response_text},
        )


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=19092)
    args = parser.parse_args()
    async with websockets.serve(handler, args.host, args.port, max_size=None):
        print(f"Fake AppBridge listening on ws://{args.host}:{args.port}", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
