#!/usr/bin/env python3
"""Mapping-only WebSocket bridge for the Android SLAM screen.

The bridge exposes only /map plus m1/m4 lifecycle commands.  It deliberately
does not publish /cmd_vel and does not start Nav2, so movement and navigation
can be owned by their separate modules.
"""

import asyncio
import json
import os
import struct
import subprocess
import threading
import time
import zlib

import rclpy
from nav_msgs.msg import OccupancyGrid
from rclpy.node import Node
from rclpy.qos import DurabilityPolicy, QoSProfile

try:
    import websockets
except ImportError as error:
    raise SystemExit("Install websockets==9.1 in the ROS container first") from error


MAGIC = b"ICAR"
MAP_PACKET = 1


class MappingBridge(Node):
    def __init__(self):
        super().__init__("mapping_app_bridge")
        self.loop = None
        self.clients = set()
        self.mapping_process = None
        self.last_map = None
        self.last_map_sent = 0.0
        self.create_subscription(
            OccupancyGrid, "/map", self.on_map,
            QoSProfile(depth=1, durability=DurabilityPolicy.TRANSIENT_LOCAL),
        )

    @property
    def mapping_active(self):
        return self.mapping_process is not None and self.mapping_process.poll() is None

    def on_map(self, message):
        if time.monotonic() - self.last_map_sent < 0.5:
            return
        self.last_map_sent = time.monotonic()
        raw = bytes((cell + 256) % 256 for cell in message.data)
        compressed = zlib.compress(raw, level=4)
        origin = message.info.origin.position
        self.last_map = struct.pack(
            "!4sBIIfffII", MAGIC, MAP_PACKET, message.info.width, message.info.height,
            message.info.resolution, origin.x, origin.y, len(raw), len(compressed),
        ) + compressed
        self.broadcast_binary(self.last_map)

    def state_message(self, detail="等待 /map 数据"):
        return {"op": "state", "mapping_active": self.mapping_active, "scan_samples": 0, "detail": detail}

    def handle(self, client, message):
        request_id = message.get("id", "")
        command = message.get("command", "")
        try:
            if command == "start_mapping":
                if not self.mapping_active:
                    self.mapping_process = subprocess.Popen(
                        ["ros2", "launch", "yahboomcar_nav", "map_gmapping_launch.py"],
                        stdout=open("/tmp/android-mapping.log", "a"), stderr=subprocess.STDOUT,
                    )
                self.reply(client, request_id, True, "已启动 GMapping")
            elif command == "save_map":
                if not self.mapping_active:
                    self.reply(client, request_id, False, "请先启动 GMapping")
                else:
                    subprocess.Popen(["ros2", "launch", "yahboomcar_nav", "save_map_launch.py"])
                    self.reply(client, request_id, True, "已请求保存 yahboomcar 地图")
            elif command == "stop_mapping":
                subprocess.call(["bash", "-lc", "pkill -INT -f 'map_gmapping_launch.py' || true"])
                self.mapping_process = None
                self.reply(client, request_id, True, "已停止 GMapping")
            else:
                self.reply(client, request_id, False, "不支持的建图命令")
            self.broadcast_text(self.state_message())
        except OSError as error:
            self.reply(client, request_id, False, f"命令执行失败：{error}")

    def reply(self, client, request_id, ok, detail):
        self.send(client, json.dumps({"op": "response", "id": request_id, "ok": ok, "message": detail}, ensure_ascii=False))

    def broadcast_text(self, payload):
        for client in tuple(self.clients):
            self.send(client, json.dumps(payload, ensure_ascii=False))

    def broadcast_binary(self, payload):
        for client in tuple(self.clients):
            self.send(client, payload)

    def send(self, client, payload):
        if self.loop:
            asyncio.run_coroutine_threadsafe(client.send(payload), self.loop)


async def serve(node, port):
    async def handler(client, path=None):
        node.clients.add(client)
        node.send(client, json.dumps(node.state_message(), ensure_ascii=False))
        if node.last_map:
            await client.send(node.last_map)
        try:
            async for raw in client:
                if isinstance(raw, str):
                    try:
                        node.handle(client, json.loads(raw))
                    except ValueError:
                        node.reply(client, "", False, "命令必须是 JSON")
        finally:
            node.clients.discard(client)

    async with websockets.serve(handler, "0.0.0.0", port, compression=None, max_size=2 ** 24):
        await asyncio.Future()


def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=9092)
    args = parser.parse_args()
    rclpy.init()
    node = MappingBridge()
    loop = asyncio.new_event_loop()
    node.loop = loop
    threading.Thread(target=lambda: loop.run_until_complete(serve(node, args.port)), daemon=True).start()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
