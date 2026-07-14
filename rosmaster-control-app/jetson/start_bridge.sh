#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$ROOT/.install-complete" ]] || ! PYTHONPATH="$ROOT/.deps${PYTHONPATH:+:$PYTHONPATH}" "$ROOT/.venv/bin/python" -c 'import rsa, websockets' >/dev/null 2>&1; then
  echo "首次启动：正在创建独立 Python 环境并安装桥接依赖……"
  bash "$ROOT/scripts/install.sh"
fi

# The face service on 9095 owns the camera and publishes the annotated stream.
# Keep this wrapper control-only by default so the bridge never competes for
# /dev/video*. Set NO_VIDEO=0 explicitly only for the legacy 9094 raw stream.
export NO_VIDEO="${NO_VIDEO:-1}"

exec bash "$ROOT/scripts/start.sh"
