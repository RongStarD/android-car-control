#!/usr/bin/env python3
"""Binary WebSocket bridge between the Android app and the ROS2 graph.

The bridge deliberately keeps raw ROS traffic inside the Jetson container.
It exposes a small, rate-limited protocol on port 9092:

* Main map and Nav2 costmaps: zlib-compressed signed cells and metadata.
* Nav2 global/local plans: compact map-frame x/y/yaw point lists.
* Robot pose: map-frame x/y/yaw.
* LaserScan: downsampled ranges.
* Small control and status messages: JSON text frames.

The Android app therefore does not need rosbridge_server or JSON copies of
large /map, /tf, /odom, or /scan messages.
"""

import asyncio
import json
import math
import queue
import struct
import threading
import time
import zlib

import rclpy
from geometry_msgs.msg import PoseStamped, PoseWithCovarianceStamped, Twist
from nav_msgs.msg import OccupancyGrid, Odometry, Path
from rclpy.node import Node
from rclpy.qos import DurabilityPolicy, QoSProfile
from sensor_msgs.msg import LaserScan
from std_msgs.msg import Bool, String
from std_srvs.srv import Trigger
from tf2_msgs.msg import TFMessage

try:
    import websockets
except ImportError as error:
    raise SystemExit(
        "The app bridge requires the Python 'websockets' package. "
        "Install it in the ROS container before starting this node."
    ) from error


MAGIC = b"ICAR"
PACKET_MAP = 1
PACKET_POSE = 2
PACKET_SCAN = 3
PACKET_LOCAL_COSTMAP = 4
PACKET_GLOBAL_COSTMAP = 5
PACKET_GLOBAL_PLAN = 6
PACKET_LOCAL_PLAN = 7
MAP_INTERVAL_SECONDS = 1.0
POSE_INTERVAL_SECONDS = 0.1
SCAN_INTERVAL_SECONDS = 0.2
LOCAL_COSTMAP_INTERVAL_SECONDS = 0.2
GLOBAL_COSTMAP_INTERVAL_SECONDS = 1.0
PATH_INTERVAL_SECONDS = 0.2
MAX_SCAN_SAMPLES = 180
MAX_PATH_POINTS = 512

SERVICE_COMMANDS = {
    "load_saved_map": "/app/load_saved_map",
    "start_mapping": "/app/start_mapping",
    "stop_mapping": "/app/stop_mapping",
    "save_map": "/app/save_map",
    "start_navigation_base": "/app/start_navigation_base",
    "start_navigation_dwa": "/app/start_navigation_dwa",
    "start_navigation_teb": "/app/start_navigation_teb",
    "cancel_navigation": "/app/cancel_navigation",
    "stop_navigation": "/app/stop_navigation",
    "emergency_stop": "/app/emergency_stop",
}
BASE_FRAMES = {"base_link", "base_footprint"}


class AppBridge(Node):
    def __init__(self, port):
        super().__init__("app_bridge")
        self.port = port
        self.loop = None
        self.bridge_clients = set()
        self.commands = queue.Queue()
        self.last_map_sent = 0.0
        self.last_pose_sent = 0.0
        self.last_scan_sent = 0.0
        self.last_local_costmap_sent = 0.0
        self.last_global_costmap_sent = 0.0
        self.last_global_plan_sent = 0.0
        self.last_local_plan_sent = 0.0
        self.last_map_packet = None
        self.last_navigation_packets = {}
        self.map_to_odom = None
        self.odom_to_base = None
        self.robot_pose = None
        self.bridge_runtime = {
            "mode": "idle",
            "phase": "unknown",
            "ready": False,
            "detail": "Waiting for Jetson runtime state",
            "error": "",
        }
        self.state = {
            "mapping_active": False,
            "navigation_ready": False,
            "navigation_active": False,
            "navigation_status": "未启动",
            "runtime": self.bridge_runtime,
        }

        map_qos = QoSProfile(depth=1, durability=DurabilityPolicy.TRANSIENT_LOCAL)
        self.create_subscription(OccupancyGrid, "/map", self.on_map, map_qos)
        # These are the real Nav2 topics confirmed on the Jetson image. They
        # are visualisation copies only; Nav2 keeps planning and obstacle
        # avoidance local and never waits for the Android connection.
        self.create_subscription(OccupancyGrid, "/local_costmap/costmap", self.on_local_costmap, map_qos)
        self.create_subscription(OccupancyGrid, "/global_costmap/costmap", self.on_global_costmap, map_qos)
        self.create_subscription(Path, "/plan", self.on_global_plan, 10)
        self.create_subscription(Path, "/local_plan", self.on_local_plan, 10)
        self.create_subscription(TFMessage, "/tf", self.on_tf, 20)
        self.create_subscription(TFMessage, "/tf_static", self.on_tf, map_qos)
        self.create_subscription(Odometry, "/odom", self.on_odom, 20)
        self.create_subscription(LaserScan, "/scan", self.on_scan, 10)
        self.create_subscription(Bool, "/app/mapping_active", self.on_mapping_active, map_qos)
        self.create_subscription(Bool, "/app/navigation_ready", self.on_navigation_ready, map_qos)
        self.create_subscription(Bool, "/app/navigation_active", self.on_navigation_active, map_qos)
        self.create_subscription(String, "/app/navigation_status", self.on_navigation_status, map_qos)
        self.create_subscription(String, "/app/runtime_state", self.on_runtime_state, map_qos)

        self.cmd_vel = self.create_publisher(Twist, "/cmd_vel", 10)
        self.goal_pose = self.create_publisher(PoseStamped, "/app/goal_pose", 10)
        self.initial_pose = self.create_publisher(PoseWithCovarianceStamped, "/initialpose", 10)
        self.bridge_services = {
            command: self.create_client(Trigger, service)
            for command, service in SERVICE_COMMANDS.items()
        }
        self.create_timer(0.02, self.drain_commands)

    def set_event_loop(self, loop):
        self.loop = loop

    def enqueue(self, client, message):
        self.commands.put((client, message))

    def drain_commands(self):
        while True:
            try:
                client, message = self.commands.get_nowait()
            except queue.Empty:
                return
            self.handle_command(client, message)

    def handle_command(self, client, message):
        request_id = message.get("id", "")
        command = message.get("command", "")
        if command in SERVICE_COMMANDS:
            service = self.bridge_services[command]
            if not service.service_is_ready():
                self.reply(client, request_id, False, f"服务未就绪：{SERVICE_COMMANDS[command]}")
                return
            future = service.call_async(Trigger.Request())
            future.add_done_callback(
                lambda result, target=client, identifier=request_id: self.on_service_response(target, identifier, result)
            )
            return
        try:
            if command == "twist":
                self.publish_twist(message)
                self.reply(client, request_id, True, "运动指令已发送")
            elif command == "initial_pose":
                self.publish_initial_pose(message)
                self.reply(client, request_id, True, "初始位姿已发布")
            elif command == "goal_pose":
                self.publish_goal_pose(message)
                self.reply(client, request_id, True, "导航目标已发布")
            else:
                self.reply(client, request_id, False, f"未知命令：{command}")
        except (KeyError, TypeError, ValueError) as error:
            self.reply(client, request_id, False, f"命令参数无效：{error}")

    def on_service_response(self, client, request_id, future):
        try:
            response = future.result()
            self.reply(client, request_id, response.success, response.message)
        except Exception as error:
            self.reply(client, request_id, False, f"ROS 服务调用失败：{error}")

    def publish_twist(self, message):
        twist = Twist()
        twist.linear.x = float(message.get("linear_x", 0.0))
        twist.linear.y = float(message.get("linear_y", 0.0))
        twist.angular.z = float(message.get("angular_z", 0.0))
        self.cmd_vel.publish(twist)

    def publish_initial_pose(self, message):
        pose = PoseWithCovarianceStamped()
        pose.header.frame_id = "map"
        pose.header.stamp = self.get_clock().now().to_msg()
        pose.pose.pose.position.x = float(message["x"])
        pose.pose.pose.position.y = float(message["y"])
        self.set_yaw(pose.pose.pose.orientation, float(message.get("yaw", 0.0)))
        pose.pose.covariance[0] = 0.25
        pose.pose.covariance[7] = 0.25
        pose.pose.covariance[35] = 0.068
        self.initial_pose.publish(pose)

    def publish_goal_pose(self, message):
        pose = PoseStamped()
        pose.header.frame_id = "map"
        pose.header.stamp = self.get_clock().now().to_msg()
        pose.pose.position.x = float(message["x"])
        pose.pose.position.y = float(message["y"])
        self.set_yaw(pose.pose.orientation, float(message.get("yaw", 0.0)))
        self.goal_pose.publish(pose)

    @staticmethod
    def set_yaw(orientation, yaw):
        orientation.z = math.sin(yaw / 2.0)
        orientation.w = math.cos(yaw / 2.0)

    def on_map(self, message):
        self.last_map_sent = self.publish_grid_if_due(
            message,
            PACKET_MAP,
            self.last_map_sent,
            MAP_INTERVAL_SECONDS,
            cache_main_map=True,
        )

    def on_local_costmap(self, message):
        self.last_local_costmap_sent = self.publish_grid_if_due(
            message,
            PACKET_LOCAL_COSTMAP,
            self.last_local_costmap_sent,
            LOCAL_COSTMAP_INTERVAL_SECONDS,
        )

    def on_global_costmap(self, message):
        self.last_global_costmap_sent = self.publish_grid_if_due(
            message,
            PACKET_GLOBAL_COSTMAP,
            self.last_global_costmap_sent,
            GLOBAL_COSTMAP_INTERVAL_SECONDS,
        )

    def publish_grid_if_due(self, message, packet_type, last_sent, interval, cache_main_map=False):
        now = time.monotonic()
        if now - last_sent < interval:
            return last_sent
        raw = bytes((cell + 256) % 256 for cell in message.data)
        compressed = zlib.compress(raw, level=4)
        origin = message.info.origin.position
        header = struct.pack(
            "!4sBIIfffII",
            MAGIC,
            PACKET_MAP,
            message.info.width,
            message.info.height,
            message.info.resolution,
            origin.x,
            origin.y,
            len(raw),
            len(compressed),
        )
        packet = header + compressed
        if cache_main_map:
            self.last_map_packet = packet
        else:
            self.last_navigation_packets[packet_type] = packet
        self.broadcast_binary(packet)
        return now

    def on_global_plan(self, message):
        self.last_global_plan_sent = self.publish_path_if_due(
            message,
            PACKET_GLOBAL_PLAN,
            self.last_global_plan_sent,
        )

    def on_local_plan(self, message):
        self.last_local_plan_sent = self.publish_path_if_due(
            message,
            PACKET_LOCAL_PLAN,
            self.last_local_plan_sent,
        )

    def publish_path_if_due(self, message, packet_type, last_sent):
        now = time.monotonic()
        if now - last_sent < PATH_INTERVAL_SECONDS:
            return last_sent
        poses = message.poses
        stride = max(1, math.ceil(len(poses) / MAX_PATH_POINTS))
        samples = poses[::stride]
        packet = bytearray(struct.pack("!4sBH", MAGIC, packet_type, len(samples)))
        for pose in samples:
            position = pose.pose.position
            packet.extend(struct.pack(
                "!fff",
                position.x,
                position.y,
                self.quaternion_yaw(pose.pose.orientation),
            ))
        encoded = bytes(packet)
        self.last_navigation_packets[packet_type] = encoded
        self.broadcast_binary(encoded)
        return now

    def on_tf(self, message):
        for transform in message.transforms:
            parent = self.normalize_frame(transform.header.frame_id)
            child = self.normalize_frame(transform.child_frame_id)
            translation = transform.transform.translation
            pose = (
                translation.x,
                translation.y,
                self.quaternion_yaw(transform.transform.rotation),
            )
            if parent == "map" and child in BASE_FRAMES:
                self.robot_pose = pose
            elif parent == "map" and child == "odom":
                self.map_to_odom = pose
                self.compose_pose()
            elif parent == "odom" and child in BASE_FRAMES:
                self.odom_to_base = pose
                self.compose_pose()
        self.publish_pose_if_due()

    def on_odom(self, message):
        if self.robot_pose is None:
            position = message.pose.pose.position
            self.robot_pose = (position.x, position.y, self.quaternion_yaw(message.pose.pose.orientation))
        self.publish_pose_if_due()

    def compose_pose(self):
        if self.map_to_odom is None or self.odom_to_base is None:
            return
        map_x, map_y, map_yaw = self.map_to_odom
        odom_x, odom_y, odom_yaw = self.odom_to_base
        self.robot_pose = (
            map_x + math.cos(map_yaw) * odom_x - math.sin(map_yaw) * odom_y,
            map_y + math.sin(map_yaw) * odom_x + math.cos(map_yaw) * odom_y,
            map_yaw + odom_yaw,
        )

    def publish_pose_if_due(self):
        if self.robot_pose is None:
            return
        now = time.monotonic()
        if now - self.last_pose_sent < POSE_INTERVAL_SECONDS:
            return
        self.last_pose_sent = now
        self.broadcast_binary(struct.pack("!4sBfff", MAGIC, PACKET_POSE, *self.robot_pose))

    def on_scan(self, message):
        now = time.monotonic()
        if now - self.last_scan_sent < SCAN_INTERVAL_SECONDS:
            return
        self.last_scan_sent = now
        ranges = message.ranges
        stride = max(1, math.ceil(len(ranges) / MAX_SCAN_SAMPLES))
        samples = [value if math.isfinite(value) else 0.0 for value in ranges[::stride]]
        header = struct.pack(
            "!4sBHff",
            MAGIC,
            PACKET_SCAN,
            len(samples),
            message.angle_min,
            message.angle_increment * stride,
        )
        self.broadcast_binary(header + struct.pack(f"!{len(samples)}f", *samples))

    def on_mapping_active(self, message):
        self.broadcast_state(mapping_active=message.data)

    def on_navigation_ready(self, message):
        self.broadcast_state(navigation_ready=message.data)

    def on_navigation_active(self, message):
        self.broadcast_state(navigation_active=message.data)

    def on_navigation_status(self, message):
        self.broadcast_state(navigation_status=message.data)

    def on_runtime_state(self, message):
        try:
            runtime = json.loads(message.data)
        except (TypeError, ValueError) as error:
            self.get_logger().warning(f"Ignoring invalid /app/runtime_state: {error}")
            return
        if not isinstance(runtime, dict):
            self.get_logger().warning("Ignoring /app/runtime_state that is not an object")
            return
        self.bridge_runtime = runtime
        self.state["runtime"] = runtime
        self.broadcast_text({"op": "runtime", **runtime})

    def broadcast_state(self, **values):
        self.state.update(values)
        self.broadcast_text({"op": "state", **values})

    def reply(self, client, request_id, ok, message):
        self.send_text(client, {"op": "response", "id": request_id, "ok": ok, "message": message})

    def send_text(self, client, message):
        self.send(client, json.dumps(message, ensure_ascii=False, separators=(",", ":")))

    def broadcast_text(self, message):
        encoded = json.dumps(message, ensure_ascii=False, separators=(",", ":"))
        for client in tuple(self.bridge_clients):
            self.send(client, encoded)

    def broadcast_binary(self, payload):
        for client in tuple(self.bridge_clients):
            self.send(client, payload)

    def send(self, client, payload):
        if self.loop is None:
            return
        future = asyncio.run_coroutine_threadsafe(client.send(payload), self.loop)
        future.add_done_callback(lambda result: result.exception() if result.cancelled() is False else None)

    @staticmethod
    def normalize_frame(frame):
        return frame.lstrip("/").lower()

    @staticmethod
    def quaternion_yaw(rotation):
        return math.atan2(
            2.0 * (rotation.w * rotation.z + rotation.x * rotation.y),
            1.0 - 2.0 * (rotation.y * rotation.y + rotation.z * rotation.z),
        )


async def websocket_server(node, host, port):
    # websockets 9.1 invokes the handler with (websocket, path), while newer
    # releases only pass websocket. The optional path supports both APIs.
    async def handler(websocket, path=None):
        node.bridge_clients.add(websocket)
        node.send_text(websocket, {"op": "hello", "protocol": 1})
        await websocket.send(json.dumps({"op": "state", **node.state}, ensure_ascii=False, separators=(",", ":")))
        await websocket.send(json.dumps({"op": "runtime", **node.bridge_runtime}, ensure_ascii=False, separators=(",", ":")))
        if node.last_map_packet is not None:
            await websocket.send(node.last_map_packet)
        for packet in node.last_navigation_packets.values():
            await websocket.send(packet)
        try:
            async for payload in websocket:
                if isinstance(payload, bytes):
                    continue
                try:
                    node.enqueue(websocket, json.loads(payload))
                except json.JSONDecodeError:
                    node.reply(websocket, "", False, "控制消息必须是 JSON 文本")
        finally:
            node.bridge_clients.discard(websocket)

    async with websockets.serve(
        handler,
        host,
        port,
        max_size=2**24,
        compression=None,
        ping_interval=20,
        ping_timeout=20,
    ):
        await asyncio.Future()


def run_server(node, host, port):
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    node.set_event_loop(loop)
    loop.run_until_complete(websocket_server(node, host, port))


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Binary WebSocket ROS2 app bridge")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=9092)
    args = parser.parse_args()

    rclpy.init()
    node = AppBridge(args.port)
    thread = threading.Thread(target=run_server, args=(node, args.host, args.port), daemon=True)
    thread.start()
    node.get_logger().info(f"App bridge listening on ws://{args.host}:{args.port}")
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
