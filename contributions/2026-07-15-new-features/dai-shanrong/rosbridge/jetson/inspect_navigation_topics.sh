#!/usr/bin/env bash
# Print the live Nav2 visualization interface. This is diagnostic-only and
# must be run after n1 plus either n3 (DWA) or n4 (TEB) have started.
# Do not enable `set -u` here: ROS Foxy's setup.bash reads optional variables
# without defaults and therefore is not nounset-safe.

source /opt/ros/foxy/setup.bash

echo "--- ROS domain ---"
echo "ROS_DOMAIN_ID=${ROS_DOMAIN_ID:-0}"

echo "--- Nav2 nodes ---"
ros2 node list | grep -E "(^|/)(bt_navigator|controller_server|planner_server|local_costmap|global_costmap|map_server)$" || true

echo "--- Navigation visualization topics ---"
ros2 topic list -t | grep -E "(^|/)(local_costmap|global_costmap)/|/(plan|local_plan|global_plan|transformed_global_plan|scan|odom|tf)$" || true

echo "--- OccupancyGrid and Path topics (authoritative type check) ---"
ros2 topic list -t | grep -E "\[(nav_msgs/msg/(OccupancyGrid|Path))\]" || true
