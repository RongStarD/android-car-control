#!/usr/bin/env python3
"""ROS2 services and goal relay used by the Android robot console.

The script runs inside the Yahboom ROS container. It exposes the existing
shortcuts m1/m4/n1/n3/n4 to the App as Trigger services and relays PoseStamped
messages from the App to Nav2's NavigateToPose action server.
"""

import os
import json
import math
import re
import signal
import subprocess
import time

import rclpy
from geometry_msgs.msg import PoseStamped, PoseWithCovarianceStamped, Twist
from nav2_msgs.action import NavigateToPose
from nav_msgs.msg import OccupancyGrid, Odometry
from rclpy.action import ActionClient
from rclpy.node import Node
from rclpy.qos import DurabilityPolicy, QoSProfile
from sensor_msgs.msg import LaserScan
from std_msgs.msg import Bool, String
from std_srvs.srv import Trigger
from tf2_msgs.msg import TFMessage


class RobotAppApi(Node):
    SENSOR_STALE_SECONDS = 2.5
    COSTMAP_STALE_SECONDS = 5.0
    STARTUP_TIMEOUT_SECONDS = 45.0
    NAVIGATION_NODES = (
        "bt_navigator",
        "controller_server",
        "planner_server",
        "map_server",
        "local_costmap",
        "global_costmap",
    )
    MAP_YAML_PATHS = (
        "/root/yahboomcar_ros2_ws/yahboomcar_ws/src/yahboomcar_nav/maps/yahboomcar.yaml",
        "/root/yahboomcar_ros2_ws/yahboomcar_ws/install/yahboomcar_nav/share/yahboomcar_nav/maps/yahboomcar.yaml",
    )

    def __init__(self):
        super().__init__("robot_app_api")
        self.mapping_process = None
        self.saved_map_publisher = None
        self.navigation_base_process = None
        self.navigation_process = None
        self.navigation_launch_file = None
        self.navigation_algorithm = None
        self.navigation_started_at = None
        self.navigation_initial_pose_at = None
        self.navigation_timeout_reported = False
        self.mapping_started_at = None
        self.navigation_base_started_at = None
        self.active_goal = None
        self.last_feedback_time = 0.0
        self.navigation_ready = None
        self.mapping_active = None
        self.navigation_active = None
        self.last_scan_at = None
        self.last_odom_at = None
        self.last_map_at = None
        self.last_local_costmap_at = None
        self.last_global_costmap_at = None
        self.has_base_to_laser_tf = False
        self.runtime = {
            "mode": "idle",
            "phase": "idle",
            "ready": False,
            "detail": "等待选择建图或导航模式",
            "error": "",
        }
        state_qos = QoSProfile(depth=1, durability=DurabilityPolicy.TRANSIENT_LOCAL)
        self.status = self.create_publisher(String, "/app/navigation_status", state_qos)
        self.ready = self.create_publisher(Bool, "/app/navigation_ready", state_qos)
        self.mapping_state = self.create_publisher(Bool, "/app/mapping_active", state_qos)
        self.navigation_state = self.create_publisher(Bool, "/app/navigation_active", state_qos)
        self.runtime_state = self.create_publisher(String, "/app/runtime_state", state_qos)
        self.runtime_state.publish(String(data=json.dumps(self.runtime, ensure_ascii=False, separators=(",", ":"))))
        self.cmd_vel = self.create_publisher(Twist, "/cmd_vel", 10)
        self.goal_subscription = self.create_subscription(PoseStamped, "/app/goal_pose", self.navigate_to_pose, 10)
        self.create_subscription(PoseWithCovarianceStamped, "/initialpose", self._on_initial_pose, 10)
        self.create_subscription(LaserScan, "/scan", self._on_scan, 10)
        self.create_subscription(Odometry, "/odom", self._on_odom, 20)
        # Volatile subscriptions accept both ordinary live publishers (GMapping)
        # and Nav2's transient-local costmaps. This supervisor needs fresh data,
        # rather than a latched map produced before the current launch.
        self.create_subscription(OccupancyGrid, "/map", self._on_map, 10)
        self.create_subscription(OccupancyGrid, "/local_costmap/costmap", self._on_local_costmap, 10)
        self.create_subscription(OccupancyGrid, "/global_costmap/costmap", self._on_global_costmap, 10)
        self.create_subscription(TFMessage, "/tf_static", self._on_tf_static, state_qos)
        self.navigate_action = ActionClient(self, NavigateToPose, "navigate_to_pose")
        self.create_service(Trigger, "/app/load_saved_map", self.load_saved_map)
        self.create_service(Trigger, "/app/start_mapping", self.start_mapping)
        self.create_service(Trigger, "/app/stop_mapping", self.stop_mapping)
        self.create_service(Trigger, "/app/save_map", self.save_map)
        self.create_service(Trigger, "/app/start_navigation_base", self.start_navigation_base)
        self.create_service(Trigger, "/app/start_navigation_dwa", self.start_navigation_dwa)
        self.create_service(Trigger, "/app/start_navigation_teb", self.start_navigation_teb)
        self.create_service(Trigger, "/app/cancel_navigation", self.cancel_navigation)
        self.create_service(Trigger, "/app/stop_navigation", self.stop_navigation)
        self.create_service(Trigger, "/app/emergency_stop", self.emergency_stop)
        self.create_timer(0.5, self._refresh_runtime_state)

    def start_mapping(self, request, response):
        if self._is_process_running(self.mapping_process) or self._node_exists("slam_gmapping"):
            self._set_runtime("mapping", "checking", False, "GMapping 已存在，正在检查传感器、TF 与地图")
            return self._reply(response, True, "GMapping 已在运行")
        self._stop_navigation_environment()
        self._stop_publishing_saved_map()
        self.mapping_process, error = self._start_launch("map_gmapping_launch.py")
        if error is None:
            self.mapping_started_at = time.monotonic()
            self._set_mapping_active(False)
            self._set_runtime("mapping", "starting", False, "已启动 m1，等待 GMapping、雷达、TF 与地图数据")
        else:
            self._set_runtime("mapping", "failed", False, "m1 启动失败", error)
        return self._reply(
            response,
            error is None,
            "已启动 m1：GMapping 重新建图将替换当前显示；保存时覆盖已保存地图" if error is None else error,
        )

    def stop_mapping(self, request, response):
        if not self._is_process_running(self.mapping_process) and not self._node_exists("slam_gmapping"):
            self._set_mapping_active(False)
            self.mapping_started_at = None
            self._set_runtime("idle", "idle", False, "建图未运行")
            return self._reply(response, True, "GMapping 未在运行，建图环境已结束")
        self._stop_launch_process(self.mapping_process, "map_gmapping_launch.py")
        self.mapping_process = None
        self.mapping_started_at = None
        self._set_mapping_active(False)
        self._set_runtime("idle", "idle", False, "建图已停止")
        return self._reply(response, True, "已结束 m1：GMapping 建图；现在可以启动 n1 导航基础节点")

    def load_saved_map(self, request, response):
        if self._is_process_running(self.mapping_process) or self._node_exists("slam_gmapping"):
            return self._reply(response, False, "GMapping 正在运行，当前显示实时建图；无需读取已保存地图")
        try:
            map_message, map_path = self._read_saved_map()
        except (OSError, ValueError) as error:
            return self._reply(response, False, f"读取已保存地图失败：{error}")
        self._publish_saved_map(map_message)
        return self._reply(response, True, f"已读取已保存地图：{map_path}")

    def save_map(self, request, response):
        if not self._node_exists("slam_gmapping"):
            return self._reply(response, False, "未检测到 slam_gmapping；请先启动建图")
        _, error = self._start_launch("save_map_launch.py")
        return self._reply(
            response,
            error is None,
            "已启动 m4：覆盖保存 maps/yahboomcar.{pgm,yaml}" if error is None else error,
        )

    def start_navigation_base(self, request, response):
        self._stop_mapping_environment()
        if self._is_process_running(self.navigation_base_process) or self._base_is_ready():
            self._set_runtime("navigation_base", "checking", False, "n1 已存在，正在检查底盘、雷达和 TF")
            return self._reply(response, True, "导航基础节点已在运行")
        self.navigation_base_process, error = self._start_launch("laser_bringup_launch.py")
        if error is None:
            self.navigation_base_started_at = time.monotonic()
            self._set_runtime("navigation_base", "starting", False, "已启动 n1，等待底盘、雷达与 TF")
        else:
            self._set_runtime("navigation_base", "failed", False, "n1 启动失败", error)
        return self._reply(response, error is None, "已启动 n1：导航基础节点" if error is None else error)

    def start_navigation_dwa(self, request, response):
        return self._start_navigation(response, "navigation_dwa_launch.py", "DWA")

    def start_navigation_teb(self, request, response):
        return self._start_navigation(response, "navigation_teb_launch.py", "TEB")

    def cancel_navigation(self, request, response):
        if self.active_goal is None:
            self._set_navigation_active(False)
            return self._reply(response, True, "当前没有正在执行的导航目标")
        self.active_goal.cancel_goal_async()
        self._set_navigation_active(False)
        self._publish_status("已请求取消导航")
        return self._reply(response, True, "已请求取消导航")

    def stop_navigation(self, request, response):
        if self.active_goal is not None:
            self.active_goal.cancel_goal_async()
        self._set_navigation_active(False)
        self._stop_navigation_stack()
        self._stop_launch_process(self.navigation_base_process, "laser_bringup_launch.py")
        self.navigation_base_process = None
        self.navigation_base_started_at = None
        self._set_runtime("idle", "idle", False, "导航环境已停止")
        return self._reply(response, True, "已结束 n1/n3/n4 导航环境")

    def emergency_stop(self, request, response):
        if self.active_goal is not None:
            self.active_goal.cancel_goal_async()
        self._set_navigation_active(False)
        self.cmd_vel.publish(Twist())
        self._publish_status("已紧急停止：已发布零速度并取消当前导航目标")
        return self._reply(response, True, "已紧急停止")

    def navigate_to_pose(self, pose):
        if not self._navigation_stack_is_ready():
            self._publish_status("Nav2 正在启动；请等待“Nav2 已就绪”后再发送目标")
            return
        if self.active_goal is not None:
            self.active_goal.cancel_goal_async()
            self._set_navigation_active(False)
        goal = NavigateToPose.Goal()
        goal.pose = pose
        self._publish_status("已发送目标，等待 Nav2 接受")
        future = self.navigate_action.send_goal_async(goal, feedback_callback=self._navigation_feedback)
        future.add_done_callback(self._goal_response)

    def _start_navigation(self, response, launch_file, algorithm):
        self._stop_mapping_environment()
        if not self._base_is_ready():
            return self._reply(response, False, "未检测到 n1 基础节点；请先启动导航基础节点")
        if self.navigation_algorithm == algorithm and (
            self._is_process_running(self.navigation_process) or self._node_exists("bt_navigator")
        ):
            message = f"{algorithm} Nav2 已在运行，将直接复用" if self._node_exists("bt_navigator") else f"{algorithm} Nav2 正在启动"
            return self._reply(response, True, message)
        if self._is_process_running(self.navigation_process) or self._node_exists("bt_navigator"):
            self._publish_status("正在停止当前导航算法")
            self._stop_navigation_stack()
        # navigation_*_launch.py starts Nav2's map_server. Stop the temporary
        # display-only publisher so Nav2 is the single source of /map.
        self._stop_publishing_saved_map()
        self._set_navigation_ready(False)
        self.navigation_process, error = self._start_launch(launch_file)
        if error is None:
            self.navigation_launch_file = launch_file
            self.navigation_algorithm = algorithm
            self.navigation_started_at = time.monotonic()
            self.navigation_initial_pose_at = None
            self.navigation_timeout_reported = False
            self._set_runtime("navigation", "starting", False, f"已启动 {algorithm}，等待 Nav2 节点、代价地图与 Action Server")
            self._publish_status(f"已启动 {algorithm} 导航，等待 Nav2 就绪")
        return self._reply(response, error is None, f"已启动 {algorithm} 导航" if error is None else error)

    def _stop_mapping_environment(self):
        """Stop m1 before entering navigation mode; App touch controls replace m3."""
        if self._is_process_running(self.mapping_process) or self._node_exists("slam_gmapping"):
            self._stop_launch_process(self.mapping_process, "map_gmapping_launch.py")
            self.mapping_process = None
        self._set_mapping_active(False)

    def _stop_navigation_environment(self):
        """Stop n1/n3/n4 before entering mapping mode."""
        if self.active_goal is not None:
            self.active_goal.cancel_goal_async()
        self._stop_navigation_stack()
        if self._is_process_running(self.navigation_base_process) or self._is_fresh(self.last_odom_at, self.SENSOR_STALE_SECONDS):
            self._stop_launch_process(self.navigation_base_process, "laser_bringup_launch.py")
            self.navigation_base_process = None

    def _goal_response(self, future):
        try:
            self.active_goal = future.result()
        except Exception as error:
            self._publish_status(f"导航目标发送失败：{error}")
            return
        if not self.active_goal.accepted:
            self.active_goal = None
            self._set_navigation_active(False)
            self._publish_status("Nav2 拒绝该导航目标")
            return
        self._set_navigation_active(True)
        self._publish_status("Nav2 已接受目标，正在导航")
        self.active_goal.get_result_async().add_done_callback(self._navigation_result)

    def _stop_navigation_stack(self):
        """Stop a Nav2 launch started by the App, or the manual n3/n4 launch."""
        process = self.navigation_process
        if self._is_process_running(process):
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGINT)
                process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    os.killpg(os.getpgid(process.pid), signal.SIGTERM)
                except OSError:
                    pass
        else:
            # n3/n4 may have been launched manually before the App connected.
            # Signalling the ros2 launch parent makes it shut down Nav2 children cleanly.
            subprocess.run(
                ["bash", "-lc", "pkill -INT -f 'navigation_(dwa|teb)_launch.py' || true"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            time.sleep(1.0)
        self.navigation_process = None
        self.navigation_launch_file = None
        self.navigation_algorithm = None
        self.navigation_started_at = None
        self.navigation_initial_pose_at = None
        self.navigation_timeout_reported = False
        self.active_goal = None
        self._set_navigation_active(False)
        self._set_navigation_ready(False)

    @staticmethod
    def _stop_launch_process(process, launch_file):
        if process is not None and process.poll() is None:
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGINT)
                process.wait(timeout=5)
                return
            except (OSError, subprocess.TimeoutExpired):
                try:
                    os.killpg(os.getpgid(process.pid), signal.SIGTERM)
                    return
                except OSError:
                    pass
        subprocess.run(
            ["bash", "-lc", f"pkill -INT -f '[{launch_file[0]}]{launch_file[1:]}' || true"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )

    def _navigation_feedback(self, feedback):
        now = time.monotonic()
        if now - self.last_feedback_time < 0.5:
            return
        self.last_feedback_time = now
        remaining = feedback.feedback.distance_remaining
        self._publish_status(f"导航中，剩余距离 {remaining:.2f} m")

    def _navigation_result(self, future):
        try:
            result = future.result()
            self._publish_status(f"导航结束，状态码 {result.status}")
        except Exception as error:
            self._publish_status(f"导航结果读取失败：{error}")
        finally:
            self.active_goal = None
            self._set_navigation_active(False)

    def _start_launch(self, launch_file):
        try:
            log_path = self._launch_log_path(launch_file)
            with open(log_path, "w", encoding="utf-8") as log_file:
                process = subprocess.Popen(
                    ["ros2", "launch", "yahboomcar_nav", launch_file],
                    stdout=log_file,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
            return process, None
        except OSError as error:
            return None, f"无法启动 {launch_file}：{error}"

    @staticmethod
    def _launch_log_path(launch_file):
        return f"/tmp/robot-app-{os.path.splitext(launch_file)[0]}.log"

    def _publish_saved_map(self, map_message):
        if self.saved_map_publisher is None:
            self.saved_map_publisher = self.create_publisher(
                OccupancyGrid,
                "/map",
                QoSProfile(depth=1, durability=DurabilityPolicy.TRANSIENT_LOCAL),
            )
        map_message.header.stamp = self.get_clock().now().to_msg()
        self.saved_map_publisher.publish(map_message)

    def _stop_publishing_saved_map(self):
        if self.saved_map_publisher is not None:
            self.destroy_publisher(self.saved_map_publisher)
            self.saved_map_publisher = None

    def _read_saved_map(self):
        yaml_path = next((path for path in self.MAP_YAML_PATHS if os.path.isfile(path)), None)
        if yaml_path is None:
            raise FileNotFoundError("未找到 maps/yahboomcar.yaml；请先完成一次建图并保存")
        # utf-8-sig accepts both ordinary UTF-8 and the BOM-prefixed YAML files
        # commonly created when maps are copied through Windows.
        with open(yaml_path, "r", encoding="utf-8-sig") as file:
            yaml_text = file.read()

        image_name = self._yaml_value(yaml_text, "image")
        if not image_name:
            # Some classroom images keep only map metadata in the YAML. m4 still
            # writes the PGM with the same basename, so use that safe convention.
            fallback_image = os.path.splitext(os.path.basename(yaml_path))[0] + ".pgm"
            fallback_path = os.path.join(os.path.dirname(yaml_path), fallback_image)
            if not os.path.isfile(fallback_path):
                raise ValueError(f"地图 YAML 缺少 image 字段，且未找到 {fallback_image}")
            image_name = fallback_image
        image_path = image_name if os.path.isabs(image_name) else os.path.join(os.path.dirname(yaml_path), image_name)
        resolution = float(self._yaml_value(yaml_text, "resolution") or "0.05")
        origin_values = [float(value) for value in re.findall(r"[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?", self._yaml_value(yaml_text, "origin") or "")]
        if len(origin_values) < 2:
            # A few classroom map exports omit origin. The PGM can still be
            # rendered safely; navigation should be calibrated before use.
            origin_values = [0.0, 0.0, 0.0]
        negate = int(float(self._yaml_value(yaml_text, "negate") or "0"))
        occupied_threshold = float(self._yaml_value(yaml_text, "occupied_thresh") or "0.65")
        free_threshold = float(self._yaml_value(yaml_text, "free_thresh") or "0.196")
        width, height, max_value, pixels = self._read_pgm(image_path)
        if max_value > 255:
            raise ValueError("仅支持每像素不超过 8 位的 PGM 地图")

        message = OccupancyGrid()
        message.header.frame_id = "map"
        message.info.map_load_time = self.get_clock().now().to_msg()
        message.info.resolution = resolution
        message.info.width = width
        message.info.height = height
        message.info.origin.position.x = origin_values[0]
        message.info.origin.position.y = origin_values[1]
        yaw = origin_values[2] if len(origin_values) > 2 else 0.0
        message.info.origin.orientation.z = math.sin(yaw / 2.0)
        message.info.origin.orientation.w = math.cos(yaw / 2.0)
        message.data = self._occupancy_cells(
            pixels, width, height, max_value, negate, occupied_threshold, free_threshold
        )
        return message, yaml_path

    @staticmethod
    def _yaml_value(yaml_text, key):
        match = re.search(rf"^\s*{re.escape(key)}\s*:\s*(.+?)\s*$", yaml_text, re.MULTILINE)
        if match is None:
            return ""
        return match.group(1).split("#", 1)[0].strip().strip("\"'")

    @staticmethod
    def _read_pgm(path):
        with open(path, "rb") as file:
            def read_token():
                token = bytearray()
                while True:
                    character = file.read(1)
                    if not character:
                        return token.decode("ascii") if token else ""
                    if character == b"#" and not token:
                        file.readline()
                    elif character.isspace():
                        if token:
                            return token.decode("ascii")
                    else:
                        token.extend(character)

            magic = read_token()
            width = int(read_token())
            height = int(read_token())
            max_value = int(read_token())
            if magic == "P5":
                pixels = list(file.read(width * height))
            elif magic == "P2":
                pixels = [int(read_token()) for _ in range(width * height)]
            else:
                raise ValueError(f"不支持的地图图像格式：{magic}")
        if len(pixels) != width * height:
            raise ValueError("PGM 地图数据长度不正确")
        return width, height, max_value, pixels

    @staticmethod
    def _occupancy_cells(pixels, width, height, max_value, negate, occupied_threshold, free_threshold):
        cells = []
        for row in range(height - 1, -1, -1):
            for column in range(width):
                value = pixels[row * width + column] / max_value
                occupancy = value if negate else 1.0 - value
                if occupancy > occupied_threshold:
                    cells.append(100)
                elif occupancy < free_threshold:
                    cells.append(0)
                else:
                    cells.append(-1)
        return cells

    def _on_scan(self, message):
        self.last_scan_at = time.monotonic()

    def _on_odom(self, message):
        self.last_odom_at = time.monotonic()

    def _on_initial_pose(self, message):
        if self.runtime["mode"] == "navigation":
            self.navigation_initial_pose_at = time.monotonic()

    def _on_map(self, message):
        self.last_map_at = time.monotonic()

    def _on_local_costmap(self, message):
        self.last_local_costmap_at = time.monotonic()

    def _on_global_costmap(self, message):
        self.last_global_costmap_at = time.monotonic()

    def _on_tf_static(self, message):
        for transform in message.transforms:
            parent = transform.header.frame_id.lstrip("/").lower()
            child = transform.child_frame_id.lstrip("/").lower()
            if {parent, child} == {"base_link", "laser"}:
                self.has_base_to_laser_tf = True

    @staticmethod
    def _is_fresh(received_at, max_age):
        return received_at is not None and time.monotonic() - received_at <= max_age

    def _base_is_ready(self):
        return (
            self._is_fresh(self.last_odom_at, self.SENSOR_STALE_SECONDS)
            and self._is_fresh(self.last_scan_at, self.SENSOR_STALE_SECONDS)
            and self.has_base_to_laser_tf
        )

    def _action_server_is_ready(self):
        try:
            return self.navigate_action.server_is_ready()
        except AttributeError:
            return self.navigate_action.wait_for_server(timeout_sec=0.0)

    def _navigation_stack_is_ready(self):
        return (
            all(self._node_exists(node) for node in self.NAVIGATION_NODES)
            and self._is_fresh(self.last_local_costmap_at, self.COSTMAP_STALE_SECONDS)
            and self._is_fresh(self.last_global_costmap_at, self.COSTMAP_STALE_SECONDS)
            and self._action_server_is_ready()
        )

    def _set_runtime(self, mode, phase, ready, detail, error=""):
        state = {
            "mode": mode,
            "phase": phase,
            "ready": ready,
            "detail": detail,
            "error": error,
        }
        if state == self.runtime:
            return
        self.runtime = state
        self.runtime_state.publish(String(data=json.dumps(state, ensure_ascii=False, separators=(",", ":"))))
        self.get_logger().info(f"Runtime state: {mode}/{phase}: {detail}")

    def _startup_timed_out(self, started_at):
        return started_at is not None and time.monotonic() - started_at >= self.STARTUP_TIMEOUT_SECONDS

    def _mark_startup_timeout(self, mode, started_at, log_launch):
        if not self._startup_timed_out(started_at):
            return False
        self._set_runtime(
            mode,
            "failed",
            False,
            "启动超时，请检查 Jetson 日志",
            self._launch_log_path(log_launch),
        )
        self._set_mapping_active(False)
        self._set_navigation_ready(False)
        return True

    def _refresh_mapping_health(self):
        if self.mapping_process is not None and self.mapping_process.poll() is not None and not self._node_exists("slam_gmapping"):
            self._set_mapping_active(False)
            self._set_runtime(
                "mapping",
                "failed",
                False,
                "m1 启动进程已退出",
                self._launch_log_path("map_gmapping_launch.py"),
            )
            return
        if not self._node_exists("slam_gmapping"):
            self._set_mapping_active(False)
            if self._mark_startup_timeout("mapping", self.mapping_started_at, "map_gmapping_launch.py"):
                return
            self._set_runtime("mapping", "waiting_node", False, "等待 slam_gmapping 节点")
            return
        if not self._is_fresh(self.last_scan_at, self.SENSOR_STALE_SECONDS):
            if self._mark_startup_timeout("mapping", self.mapping_started_at, "map_gmapping_launch.py"):
                return
            self._set_mapping_active(False)
            self._set_runtime("mapping", "waiting_scan", False, "GMapping 已启动，等待 /scan 数据")
            return
        if not self.has_base_to_laser_tf:
            if self._mark_startup_timeout("mapping", self.mapping_started_at, "map_gmapping_launch.py"):
                return
            self._set_mapping_active(False)
            self._set_runtime("mapping", "waiting_tf", False, "GMapping 已启动，等待 base_link→laser TF")
            return
        if self.last_map_at is None or (
            self.mapping_started_at is not None and self.last_map_at < self.mapping_started_at
        ):
            if self._mark_startup_timeout("mapping", self.mapping_started_at, "map_gmapping_launch.py"):
                return
            self._set_mapping_active(False)
            self._set_runtime("mapping", "waiting_map", False, "GMapping 已启动，等待 /map 输出")
            return
        self._set_mapping_active(True)
        self._set_runtime("mapping", "ready", True, "GMapping 已就绪，地图正在更新")

    def _refresh_navigation_base_health(self):
        if (
            self.navigation_base_process is not None
            and self.navigation_base_process.poll() is not None
            and not self._is_fresh(self.last_odom_at, self.SENSOR_STALE_SECONDS)
        ):
            self._set_runtime(
                "navigation_base",
                "failed",
                False,
                "n1 启动进程已退出",
                self._launch_log_path("laser_bringup_launch.py"),
            )
            return
        if not self._is_fresh(self.last_odom_at, self.SENSOR_STALE_SECONDS):
            if self._mark_startup_timeout("navigation_base", self.navigation_base_started_at, "laser_bringup_launch.py"):
                return
            self._set_runtime("navigation_base", "waiting_odom", False, "等待底盘 /odom 数据")
            return
        if not self._is_fresh(self.last_scan_at, self.SENSOR_STALE_SECONDS):
            if self._mark_startup_timeout("navigation_base", self.navigation_base_started_at, "laser_bringup_launch.py"):
                return
            self._set_runtime("navigation_base", "waiting_scan", False, "n1 已启动，等待 /scan 数据")
            return
        if not self.has_base_to_laser_tf:
            if self._mark_startup_timeout("navigation_base", self.navigation_base_started_at, "laser_bringup_launch.py"):
                return
            self._set_runtime("navigation_base", "waiting_tf", False, "n1 已启动，等待 base_link→laser TF")
            return
        self._set_runtime("navigation_base", "ready", True, "n1 已就绪：底盘、雷达与 TF 可用")

    def _refresh_navigation_health(self):
        if (
            self.navigation_process is not None
            and self.navigation_process.poll() is not None
            and not self._node_exists("bt_navigator")
        ):
            self._set_navigation_ready(False)
            self._set_runtime(
                "navigation",
                "failed",
                False,
                "Nav2 启动进程已退出",
                self._launch_log_path(self.navigation_launch_file or "navigation_dwa_launch.py"),
            )
            return
        if not self._node_exists("bt_navigator"):
            if self._mark_startup_timeout(
                "navigation", self.navigation_started_at, self.navigation_launch_file or "navigation_dwa_launch.py"
            ):
                return
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "waiting_nodes", False, "等待 Nav2 节点启动")
            return
        missing = [node for node in self.NAVIGATION_NODES if not self._node_exists(node)]
        if missing:
            if self._mark_startup_timeout(
                "navigation", self.navigation_started_at, self.navigation_launch_file or "navigation_dwa_launch.py"
            ):
                return
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "waiting_nodes", False, "等待 Nav2 节点：" + ", ".join(missing))
            return
        if self.count_subscribers("/initialpose") == 0:
            if self._mark_startup_timeout(
                "navigation", self.navigation_started_at, self.navigation_launch_file or "navigation_dwa_launch.py"
            ):
                return
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "waiting_localizer", False, "等待定位节点订阅 /initialpose")
            return
        if self.navigation_initial_pose_at is None:
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "awaiting_initial_pose", False, "Nav2 核心节点已启动，请在地图上设置并发布初始位姿")
            return
        if not self._is_fresh(self.last_local_costmap_at, self.COSTMAP_STALE_SECONDS):
            if self._mark_startup_timeout(
                "navigation", self.navigation_initial_pose_at, self.navigation_launch_file or "navigation_dwa_launch.py"
            ):
                return
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "waiting_local_costmap", False, "等待局部代价地图")
            return
        if not self._is_fresh(self.last_global_costmap_at, self.COSTMAP_STALE_SECONDS):
            if self._mark_startup_timeout(
                "navigation", self.navigation_initial_pose_at, self.navigation_launch_file or "navigation_dwa_launch.py"
            ):
                return
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "waiting_global_costmap", False, "等待全局代价地图")
            return
        if not self._action_server_is_ready():
            if self._mark_startup_timeout(
                "navigation", self.navigation_initial_pose_at, self.navigation_launch_file or "navigation_dwa_launch.py"
            ):
                return
            self._set_navigation_ready(False)
            self._set_runtime("navigation", "waiting_action", False, "等待 NavigateToPose Action Server")
            return
        self._set_navigation_ready(True)
        self._set_runtime("navigation", "ready", True, "Nav2 已就绪，可以设置初始位姿并发送目标")

    def _reply(self, response, success, message):
        response.success = success
        response.message = message
        self.get_logger().info(message)
        return response

    def _publish_status(self, text):
        self.status.publish(String(data=text))
        self.get_logger().info(text)

    def _refresh_runtime_state(self):
        mode = self.runtime["mode"]
        if mode == "mapping":
            self._refresh_mapping_health()
        elif mode == "navigation_base":
            self._refresh_navigation_base_health()
        elif mode == "navigation":
            self._refresh_navigation_health()
        else:
            self._set_mapping_active(False)
            self._set_navigation_ready(False)

    def _legacy_refresh_runtime_state(self):
        self._set_mapping_active(self._node_exists("slam_gmapping"))
        ready = self._node_exists("bt_navigator")
        self._set_navigation_ready(ready)
        if ready or self.navigation_process is None:
            return
        exit_code = self.navigation_process.poll()
        log_path = self._launch_log_path(self.navigation_launch_file or "navigation_dwa_launch.py")
        if exit_code is not None:
            self.navigation_process = None
            self.navigation_launch_file = None
            self.navigation_started_at = None
            self._publish_status(f"Nav2 启动进程已退出（代码 {exit_code}）；请查看 {log_path}")
            return
        if (
            self.navigation_started_at is not None
            and not self.navigation_timeout_reported
            and time.monotonic() - self.navigation_started_at >= 45.0
        ):
            self.navigation_timeout_reported = True
            self._publish_status(f"Nav2 超过 45 秒未就绪；请查看 {log_path}")

    def _set_navigation_ready(self, ready):
        if self.navigation_ready == ready:
            return
        self.navigation_ready = ready
        self.ready.publish(Bool(data=ready))
        if ready:
            self._publish_status("Nav2 已就绪，可以校准初始位姿并发送目标")

    def _set_mapping_active(self, active):
        if self.mapping_active == active:
            return
        self.mapping_active = active
        self.mapping_state.publish(Bool(data=active))

    def _set_navigation_active(self, active):
        if self.navigation_active == active:
            return
        self.navigation_active = active
        self.navigation_state.publish(Bool(data=active))

    def _is_process_running(self, process):
        return process is not None and process.poll() is None

    def _node_exists(self, expected_name):
        return any(name == expected_name for name, _ in self.get_node_names_and_namespaces())


def main():
    rclpy.init()
    node = RobotAppApi()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
