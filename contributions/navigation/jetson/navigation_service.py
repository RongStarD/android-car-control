#!/usr/bin/env python3
"""Nav2-only service layer for Android clients.

It owns n1/n3/n4 lifecycle, `/initialpose`, `/app/goal_pose`, and Nav2 action
cancellation.  Mapping and manual velocity output are intentionally absent.
"""

import math
import subprocess

import rclpy
from geometry_msgs.msg import PoseStamped, PoseWithCovarianceStamped
from nav2_msgs.action import NavigateToPose
from rclpy.action import ActionClient
from rclpy.node import Node
from std_srvs.srv import Trigger


class NavigationService(Node):
    def __init__(self):
        super().__init__("navigation_app_service")
        self.base_process = None
        self.navigation_process = None
        self.goal_handle = None
        self.initial_pose_publisher = self.create_publisher(PoseWithCovarianceStamped, "/initialpose", 10)
        self.initial_pose_subscription = self.create_subscription(PoseStamped, "/app/initial_pose", self.on_initial_pose, 10)
        self.goal_subscription = self.create_subscription(PoseStamped, "/app/goal_pose", self.on_goal, 10)
        self.action = ActionClient(self, NavigateToPose, "navigate_to_pose")
        self.create_service(Trigger, "/app/start_navigation_base", self.start_base)
        self.create_service(Trigger, "/app/start_navigation_dwa", lambda request, response: self.start_navigation("navigation_dwa_launch.py", response))
        self.create_service(Trigger, "/app/start_navigation_teb", lambda request, response: self.start_navigation("navigation_teb_launch.py", response))
        self.create_service(Trigger, "/app/cancel_navigation", self.cancel)
        self.create_service(Trigger, "/app/stop_navigation", self.stop)

    def start_base(self, request, response):
        if self.base_process is None or self.base_process.poll() is not None:
            self.base_process = self.launch("laser_bringup_launch.py")
        response.success, response.message = True, "导航基础节点已启动"
        return response

    def start_navigation(self, launch_file, response):
        if self.base_process is None or self.base_process.poll() is not None:
            response.success, response.message = False, "请先启动导航基础节点"
            return response
        self.stop_process(self.navigation_process)
        self.navigation_process = self.launch(launch_file)
        response.success, response.message = True, f"已启动 {launch_file}"
        return response

    def cancel(self, request, response):
        if self.goal_handle is not None:
            self.goal_handle.cancel_goal_async()
            self.goal_handle = None
        response.success, response.message = True, "已取消导航目标"
        return response

    def stop(self, request, response):
        self.cancel(request, response)
        self.stop_process(self.navigation_process)
        self.stop_process(self.base_process)
        self.navigation_process = self.base_process = None
        response.success, response.message = True, "导航环境已停止"
        return response

    def on_goal(self, message):
        if self.navigation_process is None or self.navigation_process.poll() is not None:
            self.get_logger().warning("Ignoring goal: Nav2 is not running")
            return
        if not self.action.wait_for_server(timeout_sec=2.0):
            self.get_logger().warning("Ignoring goal: NavigateToPose action is unavailable")
            return
        goal = NavigateToPose.Goal()
        goal.pose = message
        self.action.send_goal_async(goal).add_done_callback(self.on_goal_accepted)

    def on_initial_pose(self, message):
        orientation = message.pose.orientation
        yaw = math.atan2(
            2.0 * (orientation.w * orientation.z + orientation.x * orientation.y),
            1.0 - 2.0 * (orientation.y * orientation.y + orientation.z * orientation.z),
        )
        position = message.pose.position
        self.publish_initial_pose(position.x, position.y, yaw)

    def on_goal_accepted(self, future):
        self.goal_handle = future.result()
        if not self.goal_handle.accepted:
            self.get_logger().warning("Navigation goal rejected")
            self.goal_handle = None

    def publish_initial_pose(self, x, y, yaw):
        message = PoseWithCovarianceStamped()
        message.header.frame_id = "map"
        message.header.stamp = self.get_clock().now().to_msg()
        message.pose.pose.position.x = x
        message.pose.pose.position.y = y
        message.pose.pose.orientation.z = math.sin(yaw / 2.0)
        message.pose.pose.orientation.w = math.cos(yaw / 2.0)
        message.pose.covariance[0] = message.pose.covariance[7] = 0.25
        message.pose.covariance[35] = 0.068
        self.initial_pose_publisher.publish(message)

    @staticmethod
    def launch(file_name):
        return subprocess.Popen(["ros2", "launch", "yahboomcar_nav", file_name])

    @staticmethod
    def stop_process(process):
        if process is not None and process.poll() is None:
            process.terminate()


def main():
    rclpy.init()
    node = NavigationService()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.stop_process(node.navigation_process)
        node.stop_process(node.base_process)
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
