#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; PID_FILE="$ROOT/run/bridge.pid"
if [[ ! -s "$PID_FILE" ]]; then echo "控制桥未运行"; exit 0; fi
pid="$(cat "$PID_FILE")"
if [[ ! "$pid" =~ ^[0-9]+$ ]] || ! kill -0 "$pid" 2>/dev/null; then rm -f "$PID_FILE"; echo "控制桥未运行"; exit 0; fi
if [[ -r "/proc/$pid/cmdline" ]] && ! tr '\0' ' ' <"/proc/$pid/cmdline" | grep -q 'bridge_server.py'; then echo "PID 文件指向的不是控制桥，拒绝发送信号" >&2; exit 1; fi
kill -INT "$pid"
for _ in $(seq 1 50); do if ! kill -0 "$pid" 2>/dev/null; then rm -f "$PID_FILE"; echo "控制桥已安全停止"; exit 0; fi; sleep .1; done
kill -TERM "$pid"
for _ in $(seq 1 30); do if ! kill -0 "$pid" 2>/dev/null; then rm -f "$PID_FILE"; echo "控制桥已安全停止"; exit 0; fi; sleep .1; done
echo "控制桥未能正常退出；请先确认小车已停止，再人工处理 PID=$pid" >&2; exit 1
