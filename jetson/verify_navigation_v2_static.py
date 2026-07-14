#!/usr/bin/env python3
"""Static invariants for the V2 Jetson navigation safety patch."""

import ast
import pathlib
import sys


def class_method(tree, class_name, method_name):
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name == class_name:
            for item in node.body:
                if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name == method_name:
                    return item
    raise AssertionError(f"missing {class_name}.{method_name}")


def source_segment(text, node):
    lines = text.splitlines()
    return "\n".join(lines[node.lineno - 1:node.end_lineno])


def require(segment, *needles):
    for needle in needles:
        assert needle in segment, f"missing invariant {needle!r}"


def main():
    app_path, api_path = map(pathlib.Path, sys.argv[1:3])
    app_text = app_path.read_text(encoding="utf-8")
    api_text = api_path.read_text(encoding="utf-8")
    app_tree = ast.parse(app_text)
    api_tree = ast.parse(api_text)
    assert "_legacy_" not in api_text, "obsolete legacy navigation methods must be removed"

    grid = source_segment(app_text, class_method(app_tree, "AppBridge", "publish_grid_if_due"))
    require(grid, "packet_type,")
    assert "PACKET_MAP," not in grid, "costmaps would still be mislabeled as PACKET_MAP"

    drain = source_segment(app_text, class_method(app_tree, "AppBridge", "drain_commands"))
    require(
        drain,
        '"_safety_stop"',
        "self.cmd_vel.publish(Twist())",
        'self.bridge_services["emergency_stop"]',
        "call_async(Trigger.Request())",
    )
    require(app_text, "if not node.bridge_clients:", 'enqueue_safety_stop("last WebSocket client disconnected")')

    goal_bridge = source_segment(app_text, class_method(app_tree, "AppBridge", "on_navigation_goal_state"))
    require(goal_bridge, 'goal["sequence"]', 'goal["state"]', 'goal.get("result_status")')
    require(
        app_text,
        '"navigation_goal": {',
        '"navigation_goal_sequence": 0',
        '"navigation_goal_state": "idle"',
        '"navigation_result_status": None',
        "navigation_goal_sequence=sequence",
        "navigation_goal_state=state",
        "navigation_result_status=result_status",
        '"local_overlay_frame": "unknown"',
    )
    initial_pose = source_segment(app_text, class_method(app_tree, "AppBridge", "publish_initial_pose"))
    assert "header.stamp" not in initial_pose, "initial pose must use ROS zero stamp/latest TF"

    local_costmap = source_segment(app_text, class_method(app_tree, "AppBridge", "on_local_costmap"))
    require(local_costmap, 'bridge_runtime.get("mode") != "navigation"', "message.header.frame_id", "local_overlay_frame")
    local_plan = source_segment(app_text, class_method(app_tree, "AppBridge", "on_local_plan"))
    require(
        local_plan,
        'bridge_runtime.get("mode") != "navigation"',
        'frame == "odom"',
        "self.map_to_odom",
        'local_plan_frame="map"',
    )
    path = source_segment(app_text, class_method(app_tree, "AppBridge", "publish_path_if_due"))
    require(path, "math.cos(map_yaw)", "math.sin(map_yaw)")
    global_costmap = source_segment(app_text, class_method(app_tree, "AppBridge", "on_global_costmap"))
    global_plan = source_segment(app_text, class_method(app_tree, "AppBridge", "on_global_plan"))
    require(global_costmap, 'bridge_runtime.get("mode") != "navigation"')
    require(global_plan, 'bridge_runtime.get("mode") != "navigation"')
    runtime = source_segment(app_text, class_method(app_tree, "AppBridge", "on_runtime_state"))
    require(runtime, 'next_mode != "navigation"', 'next_phase == "starting"', "reset_navigation_overlays")
    reset_overlays = source_segment(app_text, class_method(app_tree, "AppBridge", "reset_navigation_overlays"))
    require(
        reset_overlays,
        "last_navigation_packets.clear()",
        'local_overlay_frame="unknown"',
        'local_plan_frame="waiting_transform"',
    )

    cancel = source_segment(api_text, class_method(api_tree, "RobotAppApi", "cancel_navigation"))
    require(cancel, '_request_current_goal_cancel("cancel")')
    request_cancel = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_request_current_goal_cancel"))
    require(request_cancel, "_current_goal_sequence", "cancel_requested_sequences.add", "_start_zero_guard")
    goal_response = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_goal_response"))
    require(
        goal_response,
        "cancel_requested_sequences",
        "_request_goal_handle_cancel",
        "terminal_goal_sequences",
    )

    guard = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_safety_guard_tick"))
    require(guard, "zero_guard_sequences", "self.cmd_vel.publish(Twist())")
    require(api_text, "self.create_timer(0.02, self._safety_guard_tick)")
    state = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_set_goal_state"))
    require(state, '"sequence"', '"state"', '"result_status"', "navigation_goal_state.publish")

    ready = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_initial_pose_localizer_is_ready"))
    require(ready, 'self._node_exists("amcl")', 'self.count_subscribers("/initialpose") > 1')
    health = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_refresh_navigation_health"))
    stack = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_navigation_stack_is_ready"))
    require(health, "_initial_pose_localizer_is_ready")
    require(stack, "_initial_pose_localizer_is_ready")
    accepted = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_initial_pose_is_accepted"))
    require(accepted, "last_amcl_pose_at", "navigation_initial_pose_at")
    require(health, "_initial_pose_is_accepted", "waiting_amcl_pose", "AMCL 未接受初始位姿")
    require(stack, "_initial_pose_is_accepted")
    on_initial = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_on_initial_pose"))
    require(on_initial, "last_amcl_pose_at = None", "last_local_costmap_at = None")
    on_amcl = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_on_amcl_pose"))
    require(on_amcl, "last_amcl_pose_at = time.monotonic()")
    on_tf = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_on_tf"))
    require(on_tf, 'parent == "odom"', '"base_footprint"', "last_odom_to_base_at")
    base_ready = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_base_is_ready"))
    require(base_ready, "last_odom_to_base_at", "SENSOR_STALE_SECONDS")
    reset_base = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_reset_base_health"))
    require(reset_base, "last_odom_to_base_at = None", "has_base_to_laser_tf = False", "destroy_subscription")
    base_health = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_refresh_navigation_base_health"))
    require(base_health, "last_odom_to_base_at", "waiting_tf")
    require(health, '"waiting_base"', "_base_is_ready")
    costmap_current = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_costmap_is_current"))
    require(costmap_current, "received_at >= self.last_amcl_pose_at")
    start_navigation = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_start_navigation"))
    stop_stack = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_stop_navigation_stack"))
    require(start_navigation, "last_local_costmap_at = None", "last_global_costmap_at = None")
    require(stop_stack, "last_local_costmap_at = None", "last_global_costmap_at = None")

    base = source_segment(api_text, class_method(api_tree, "RobotAppApi", "start_navigation_base"))
    require(base, 'self._node_exists("base_node")', 'self._node_exists("sllidar_node")', "_stop_manual_keyboard_control")
    manual = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_stop_manual_keyboard_control"))
    require(manual, "pkill", "yahboom_keyboard")
    assert "[j]oy_ctrl" not in manual, "navigation start must retain n1 joy_ctrl"

    result = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_navigation_result"))
    require(result, '4: "succeeded"', '5: "canceled"', '6: "aborted"', "_finish_goal_locally")
    emergency = source_segment(api_text, class_method(api_tree, "RobotAppApi", "emergency_stop"))
    require(emergency, "_request_current_goal_cancel", "_stop_navigation_stack", '"navigation_base"')
    assert "_stop_launch_process(self.navigation_base_process" not in emergency, "emergency must retain n1"

    restart = pathlib.Path(__file__).with_name("restart_v2_runtime.sh").read_text(encoding="utf-8")
    require(
        restart,
        'export ROS_DOMAIN_ID="30"',
        "stop_all robot_app_api",
        "stop_all app_bridge",
        "kill -TERM",
        "assert_one_domain30 robot_app_api",
        "assert_one_domain30 app_bridge",
    )

    print("Static V2 navigation safety checks: PASS")


if __name__ == "__main__":
    main()
