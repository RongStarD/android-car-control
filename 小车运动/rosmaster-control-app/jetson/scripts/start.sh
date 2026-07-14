#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; RUN_DIR="$ROOT/run"; PID_FILE="$RUN_DIR/bridge.pid"; LOG_FILE="$RUN_DIR/bridge.log"
PYTHON="${PYTHON:-$ROOT/.venv/bin/python}"
if [[ ! -x "$PYTHON" ]]; then echo "未找到虚拟环境，请先运行 scripts/install.sh" >&2; exit 1; fi
export PYTHONPATH="$ROOT/.deps${PYTHONPATH:+:$PYTHONPATH}"
mkdir -p "$RUN_DIR"
if [[ -s "$PID_FILE" ]]; then old_pid="$(cat "$PID_FILE")"; if [[ "$old_pid" =~ ^[0-9]+$ ]] && kill -0 "$old_pid" 2>/dev/null; then echo "控制桥已在运行，PID=$old_pid" >&2; exit 1; fi; rm -f "$PID_FILE"; fi
args=(--host "${BRIDGE_HOST:-0.0.0.0}" --ws-port "${WS_PORT:-9093}" --video-port "${VIDEO_PORT:-9094}" --camera-device "${CAMERA_DEVICE:-0}" --watchdog-ms "${WATCHDOG_MS:-700}" --vendor-root "${VENDOR_ROOT:-/home/jetson/Rosmaster-App/rosmaster}" --lock-file "$RUN_DIR/bridge.lock")
[[ "${MOCK:-0}" == "1" ]] && args+=(--mock)
[[ "${NO_CAMERA:-0}" == "1" ]] && args+=(--no-camera)
nohup "$PYTHON" "$ROOT/bridge_server.py" "${args[@]}" >"$LOG_FILE" 2>&1 & pid=$!; echo "$pid" >"$PID_FILE"; sleep 1
if ! kill -0 "$pid" 2>/dev/null; then rm -f "$PID_FILE"; echo "控制桥启动失败，请查看 $LOG_FILE" >&2; tail -n 20 "$LOG_FILE" >&2 || true; exit 1; fi
echo "控制桥已启动，PID=$pid"; echo "WebSocket: ws://<Jetson-IP>:${WS_PORT:-9093}"; echo "视频: http://<Jetson-IP>:${VIDEO_PORT:-9094}/video.mjpg"
