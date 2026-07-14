#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="/tmp"
PORT="9092"
LIVE=false
DISCONNECT_STOP_TEST=false

usage() {
    cat <<'EOF'
Usage: bash verify_navigation_v2.sh [--target DIR] [--port PORT] [--live]
                                    [--disconnect-stop-test]

The default verification is static and never touches ROS runtime state.
--live checks the already-running API/bridge but starts no robot launch file.
--disconnect-stop-test additionally proves that closing the only WebSocket
client emits a zero Twist; disconnect every App client before using it.
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
        --live)
            LIVE=true
            shift
            ;;
        --disconnect-stop-test)
            LIVE=true
            DISCONNECT_STOP_TEST=true
            shift
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

APP="$TARGET_DIR/app_bridge.py"
API="$TARGET_DIR/robot_app_api.py"
[[ -f "$APP" ]] || { echo "Missing $APP" >&2; exit 1; }
[[ -f "$API" ]] || { echo "Missing $API" >&2; exit 1; }

python3 -m py_compile "$APP" "$API"
: <<'LEGACY_STATIC_CHECK'
import ast
import pathlib
import sys

app_path, api_path = map(pathlib.Path, sys.argv[1:])
app_text = app_path.read_text(encoding="utf-8")
api_text = api_path.read_text(encoding="utf-8")
app_tree = ast.parse(app_text)
api_tree = ast.parse(api_text)


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


grid = source_segment(app_text, class_method(app_tree, "AppBridge", "publish_grid_if_due"))
assert "packet_type," in grid, "grid packet header still ignores packet_type"
assert "PACKET_MAP," not in grid, "costmaps would still be mislabeled as PACKET_MAP"

drain = source_segment(app_text, class_method(app_tree, "AppBridge", "drain_commands"))
assert '"_safety_stop"' in drain and "self.cmd_vel.publish(Twist())" in drain
assert "if not node.bridge_clients:" in app_text
assert 'enqueue_safety_stop("last WebSocket client disconnected")' in app_text

cancel_node = class_method(api_tree, "RobotAppApi", "cancel_navigation")
cancel = source_segment(api_text, cancel_node)
assert "add_done_callback" in cancel and "等待 Nav2 确认及最终结果" in cancel
# The initial no-goal branch may clear the flag, but the active-goal path must
# not claim completion before the action result arrives.
active_cancel_path = "\n".join(api_text.splitlines()[cancel_node.body[0].end_lineno:cancel_node.end_lineno])
assert "self._set_navigation_active(False)" not in active_cancel_path

stop_node = class_method(api_tree, "RobotAppApi", "stop_navigation")
stop = source_segment(api_text, stop_node)
zero_index = stop.index("self.cmd_vel.publish(Twist())")
cancel_index = stop.index("cancel_goal_async")
stack_index = stop.index("_stop_navigation_stack")
assert zero_index < cancel_index < stack_index, "stop_navigation must publish zero first"

ready = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_initial_pose_localizer_is_ready"))
assert 'self._node_exists("amcl")' in ready
assert 'self.count_subscribers("/initialpose") > 1' in ready
health = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_refresh_navigation_health"))
assert "_initial_pose_localizer_is_ready" in health
stack = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_navigation_stack_is_ready"))
assert "_initial_pose_localizer_is_ready" in stack

base = source_segment(api_text, class_method(api_tree, "RobotAppApi", "start_navigation_base"))
assert 'self._node_exists("base_node")' in base
assert 'self._node_exists("sllidar_node")' in base

result = source_segment(api_text, class_method(api_tree, "RobotAppApi", "_navigation_result"))
assert "导航目标已取消" in result and "if self.active_goal is goal:" in result

print("Static V2 navigation safety checks: PASS")
LEGACY_STATIC_CHECK
python3 "$SCRIPT_DIR/verify_navigation_v2_static.py" "$APP" "$API"

if [[ "$LIVE" != true ]]; then
    echo "Static verification complete; ROS runtime was not changed."
    exit 0
fi

set +u
# Match the classroom vehicle environment even when this script is invoked
# through a non-interactive docker exec shell.
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

require_one_domain30() {
    local name="$1"
    local pattern="$2"
    local -a pids=()
    mapfile -t pids < <(pgrep -f "$pattern" || true)
    if ((${#pids[@]} != 1)); then
        echo "Expected exactly one $name process, found ${#pids[@]}: ${pids[*]-}" >&2
        return 1
    fi
    if ! tr '\0' '\n' <"/proc/${pids[0]}/environ" | grep -qx 'ROS_DOMAIN_ID=30'; then
        echo "$name PID ${pids[0]} is not running with ROS_DOMAIN_ID=30" >&2
        return 1
    fi
}

require_one_domain30 robot_app_api '[r]obot_app_api.py'
require_one_domain30 app_bridge '[a]pp_bridge.py'

SERVICES="$(ros2 service list)"
for service in \
    /app/start_navigation_base \
    /app/start_navigation_dwa \
    /app/start_navigation_teb \
    /app/cancel_navigation \
    /app/stop_navigation \
    /app/emergency_stop; do
    grep -qx "$service" <<<"$SERVICES" || { echo "Missing ROS service: $service" >&2; exit 1; }
done

python3 - "$PORT" <<'PY'
import socket
import sys

# A full WebSocket connect followed by disconnect is intentionally a safety
# event. The ordinary --live check therefore verifies only that the listener
# accepts TCP; --disconnect-stop-test performs and audits the WebSocket event.
with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=1):
    pass
print("AppBridge TCP endpoint: PASS")
PY

if ros2 node list | grep -qx '/amcl'; then
    echo "AMCL is currently running; /initialpose endpoints:"
    ros2 topic info -v /initialpose 2>/dev/null || ros2 topic info /initialpose
else
    echo "AMCL is not currently running (expected while navigation is stopped)."
fi

if [[ "$DISCONNECT_STOP_TEST" != true ]]; then
    echo "Live endpoint verification: PASS. No navigation or mapping process was started."
    exit 0
fi

command -v ss >/dev/null || { echo "ss is required for exclusive-client safety check" >&2; exit 1; }
if ss -Htn state established | awk -v port=":$PORT" '$4 ~ (port "$") {found=1} END {exit found ? 0 : 1}'; then
    echo "Port $PORT already has a client. Disconnect the App before the stop test." >&2
    exit 1
fi

CAPTURE="/tmp/car-app-v2-disconnect-stop.$$.yaml"
BRIDGE_LOG="/tmp/app-bridge.log"
LOG_START=0
if [[ -f "$BRIDGE_LOG" ]]; then
    LOG_START="$(wc -l <"$BRIDGE_LOG")"
fi
rm -f "$CAPTURE"
timeout 6 ros2 topic echo --once /cmd_vel >"$CAPTURE" &
ECHO_PID=$!
sleep 0.3

python3 - "$PORT" <<'PY'
import asyncio
import json
import sys
import websockets


async def connect_and_close():
    async with websockets.connect(f"ws://127.0.0.1:{sys.argv[1]}", max_size=2**24) as socket:
        received = []
        hello = False
        structured_state = False
        for _ in range(12):
            message = await asyncio.wait_for(socket.recv(), timeout=3)
            if isinstance(message, bytes):
                continue
            payload = json.loads(message)
            received.append(payload)
            if payload.get("op") == "hello" and payload.get("protocol") == 1:
                hello = True
            if payload.get("op") == "state" and isinstance(payload.get("navigation_goal"), dict):
                goal = payload["navigation_goal"]
                assert {"sequence", "state", "result_status"} <= set(goal), goal
                assert payload.get("navigation_goal_sequence") == goal["sequence"], payload
                assert payload.get("navigation_goal_state") == goal["state"], payload
                assert payload.get("navigation_result_status") == goal["result_status"], payload
                assert "local_overlay_frame" in payload, payload
                structured_state = True
            if hello and structured_state:
                return
        raise AssertionError({"missing_hello_or_structured_state": received})


asyncio.get_event_loop().run_until_complete(connect_and_close())
PY

wait "$ECHO_PID" || {
    echo "No /cmd_vel message observed after the last client disconnected." >&2
    rm -f "$CAPTURE"
    exit 1
}

python3 - "$CAPTURE" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
values = [float(value) for value in re.findall(r"\b[xyz]:\s*([-+0-9.eE]+)", text)]
assert len(values) >= 6, text
assert all(abs(value) <= 1e-9 for value in values), text
print("Last-client disconnect zero Twist: PASS")
PY

disconnect_service_seen=false
for _ in $(seq 1 120); do
    if [[ -f "$BRIDGE_LOG" ]] && tail -n "+$((LOG_START + 1))" "$BRIDGE_LOG" | grep -q 'Disconnect emergency_stop service accepted'; then
        disconnect_service_seen=true
        break
    fi
    sleep 0.1
done
if [[ "$disconnect_service_seen" != true ]]; then
    echo "Disconnect published zero but did not prove /app/emergency_stop was accepted." >&2
    tail -n 80 "$BRIDGE_LOG" >&2 || true
    rm -f "$CAPTURE"
    exit 1
fi
echo "Last-client disconnect emergency_stop service: PASS"
rm -f "$CAPTURE"
