#!/usr/bin/env python3
"""Movement-only WebSocket bridge: JSON `twist` commands become ROS `/cmd_vel`."""

import asyncio
import json
import queue
import threading

import rclpy
from geometry_msgs.msg import Twist
from rclpy.node import Node

try:
    import websockets
except ImportError as error:
    raise SystemExit("Install websockets==9.1 in the ROS container first") from error


class MovementBridge(Node):
    def __init__(self):
        super().__init__("movement_bridge")
        self.commands = queue.Queue()
        self.publisher = self.create_publisher(Twist, "/cmd_vel", 10)
        self.create_timer(0.02, self.consume_commands)

    def consume_commands(self):
        while not self.commands.empty():
            payload, reply = self.commands.get_nowait()
            command = payload.get("command")
            if command == "emergency_stop":
                self.publish_twist(0.0, 0.0, 0.0)
                reply(True, "已发布紧急停止")
            elif command == "twist":
                try:
                    self.publish_twist(
                        float(payload.get("linear_x", 0.0)),
                        float(payload.get("linear_y", 0.0)),
                        float(payload.get("angular_z", 0.0)),
                    )
                    reply(True, "移动指令已发布")
                except (TypeError, ValueError) as error:
                    reply(False, f"移动参数无效：{error}")
            else:
                reply(False, "不支持的移动命令")

    def publish_twist(self, linear_x, linear_y, angular_z):
        message = Twist()
        message.linear.x = linear_x
        message.linear.y = linear_y
        message.angular.z = angular_z
        self.publisher.publish(message)


async def serve(node, port):
    async def handler(client, path=None):
        loop = asyncio.get_running_loop()
        async for raw in client:
            try:
                request = json.loads(raw)
            except (TypeError, ValueError):
                await client.send(json.dumps({"ok": False, "message": "命令必须是 JSON"}, ensure_ascii=False))
                continue
            response = asyncio.get_running_loop().create_future()
            node.commands.put((request, lambda ok, message: loop.call_soon_threadsafe(
                response.set_result, {"ok": ok, "message": message}
            )))
            await client.send(json.dumps(await response, ensure_ascii=False))

    async with websockets.serve(handler, "0.0.0.0", port, compression=None):
        await asyncio.Future()


def main():
    rclpy.init()
    node = MovementBridge()
    loop = asyncio.new_event_loop()
    threading.Thread(target=lambda: loop.run_until_complete(serve(node, 9093)), daemon=True).start()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.publish_twist(0.0, 0.0, 0.0)
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
