#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! -f "$ROOT/.install-complete" ]] || ! PYTHONPATH="$ROOT/.deps${PYTHONPATH:+:$PYTHONPATH}" "$ROOT/.venv/bin/python" -c 'import rsa, websockets' >/dev/null 2>&1; then
  echo "首次启动：正在创建独立 Python 环境并安装桥接依赖……"
  bash "$ROOT/scripts/install.sh"
fi

exec bash "$ROOT/scripts/start.sh"
