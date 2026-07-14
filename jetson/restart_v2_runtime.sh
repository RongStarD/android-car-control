#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="/tmp"
PORT="9092"

usage() {
    cat <<'EOF'
Usage: bash restart_v2_runtime.sh [--target DIR] [--port PORT]

Restarts only robot_app_api.py and app_bridge.py. It does not start m1, n1,
navigation_dwa_launch.py, or navigation_teb_launch.py.
EOF
}

while (($#)); do
    case "$1" in
        --target)
            TARGET_DIR="$2"
            shift 2
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

API="$TARGET_DIR/robot_app_api.py"
BRIDGE="$TARGET_DIR/app_bridge.py"
[[ -f "$API" ]] || { echo "Missing $API" >&2; exit 1; }
[[ -f "$BRIDGE" ]] || { echo "Missing $BRIDGE" >&2; exit 1; }

set +u
# The classroom image defines these only in the interactive /root/.bashrc.
# docker exec with a non-interactive shell otherwise silently puts the API and
# Bridge in ROS domain 0 while the vehicle nodes remain in domain 30.
# V2 and the classroom vehicle nodes must share domain 30. Do not inherit a
# stale domain 0 from an older docker exec session.
export ROS_DOMAIN_ID="30"
export ROBOT_TYPE="${ROBOT_TYPE:-x3}"
export RPLIDAR_TYPE="${RPLIDAR_TYPE:-a1}"
export CAMERA_TYPE="${CAMERA_TYPE:-astraplus}"
# shellcheck disable=SC1091
source /opt/ros/foxy/setup.bash
if [[ -f /root/yahboomcar_ros2_ws/yahboomcar_ws/install/setup.bash ]]; then
    # shellcheck disable=SC1091
    source /root/yahboomcar_ros2_ws/yahboomcar_ws/install/setup.bash
fi
if [[ -f /root/yahboomcar_ros2_ws/software/library_ws/install/setup.bash ]]; then
    # shellcheck disable=SC1091
    source /root/yahboomcar_ros2_ws/software/library_ws/install/setup.bash
fi
set -u

echo "Starting V2 runtime in ROS_DOMAIN_ID=$ROS_DOMAIN_ID, robot=$ROBOT_TYPE, lidar=$RPLIDAR_TYPE."

python3 -m py_compile "$API" "$BRIDGE"
python3 -c 'import websockets' >/dev/null

# Make the restart itself fail-safe even when the previously running bridge is
# an older build that does not publish zero on disconnect.
timeout 3 ros2 topic pub --once /cmd_vel geometry_msgs/msg/Twist '{}' >/dev/null 2>&1 || true

process_pids() {
    pgrep -f "$1" || true
}

wait_gone() {
    local pattern="$1"
    local attempts="$2"
    for _ in $(seq 1 "$attempts"); do
        [[ -z "$(process_pids "$pattern")" ]] && return 0
        sleep 0.1
    done
    return 1
}

stop_all() {
    local name="$1"
    local pattern="$2"
    local pids
    pids="$(process_pids "$pattern")"
    [[ -z "$pids" ]] && return 0
    echo "Stopping old $name process(es): ${pids//$'\n'/ }"
    # shellcheck disable=SC2086
    kill -INT $pids 2>/dev/null || true
    if wait_gone "$pattern" 30; then
        return 0
    fi
    pids="$(process_pids "$pattern")"
    echo "$name ignored SIGINT; sending SIGTERM to: ${pids//$'\n'/ }" >&2
    # shellcheck disable=SC2086
    kill -TERM $pids 2>/dev/null || true
    if wait_gone "$pattern" 30; then
        return 0
    fi
    echo "Refusing to start duplicate $name; remaining PIDs: $(process_pids "$pattern" | tr '\n' ' ')" >&2
    return 1
}

assert_one_domain30() {
    local name="$1"
    local pattern="$2"
    local -a pids=()
    mapfile -t pids < <(process_pids "$pattern")
    if ((${#pids[@]} != 1)); then
        echo "Expected exactly one $name process, found ${#pids[@]}: ${pids[*]-}" >&2
        return 1
    fi
    if ! tr '\0' '\n' <"/proc/${pids[0]}/environ" | grep -qx 'ROS_DOMAIN_ID=30'; then
        echo "$name PID ${pids[0]} is not running with ROS_DOMAIN_ID=30" >&2
        return 1
    fi
}

stop_all robot_app_api '[r]obot_app_api.py'
stop_all app_bridge '[a]pp_bridge.py'

nohup python3 "$API" >/tmp/robot-app-api.log 2>&1 &

api_ready=false
for _ in $(seq 1 20); do
    if ros2 service list 2>/dev/null | grep -qx '/app/start_navigation_base'; then
        api_ready=true
        break
    fi
    sleep 0.5
done
if [[ "$api_ready" != true ]]; then
    echo "robot_app_api did not become ready; log follows:" >&2
    tail -n 100 /tmp/robot-app-api.log >&2 || true
    exit 1
fi
assert_one_domain30 robot_app_api '[r]obot_app_api.py'

nohup python3 "$BRIDGE" --host 0.0.0.0 --port "$PORT" >/tmp/app-bridge.log 2>&1 &

bridge_ready=false
for _ in $(seq 1 20); do
    if python3 - "$PORT" <<'PY' >/dev/null 2>&1
import socket
import sys
with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=0.2):
    pass
PY
    then
        bridge_ready=true
        break
    fi
    sleep 0.5
done
if [[ "$bridge_ready" != true ]]; then
    echo "app_bridge did not stay running; log follows:" >&2
    tail -n 100 /tmp/app-bridge.log >&2 || true
    exit 1
fi
assert_one_domain30 app_bridge '[a]pp_bridge.py'

echo "V2 runtime restarted safely: exactly one Domain 30 API and Bridge; AppBridge port $PORT accepts connections."
echo "No mapping or navigation launch file was started by this script."
