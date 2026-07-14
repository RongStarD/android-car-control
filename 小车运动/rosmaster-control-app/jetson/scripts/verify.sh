#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; PYTHON="${PYTHON:-$ROOT/.venv/bin/python}"
[[ -x "$PYTHON" ]] || PYTHON=python3
export PYTHONPATH="$ROOT/.deps${PYTHONPATH:+:$PYTHONPATH}"
cd "$ROOT"; PYTHONDONTWRITEBYTECODE=1 "$PYTHON" -m py_compile bridge_server.py rosmaster_bridge/*.py tests/*.py
PYTHONDONTWRITEBYTECODE=1 "$PYTHON" -m unittest discover -s tests -v
"$PYTHON" bridge_server.py --help >/dev/null
echo "静态检查和纯逻辑测试全部通过"
