#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
"$PYTHON" - <<'PY'
import sys
if sys.version_info < (3,8): raise SystemExit("需要 Python 3.8 或更高版本")
PY
"$PYTHON" -m venv --clear --without-pip --system-site-packages "$ROOT/.venv"
mkdir -p "$ROOT/.deps"
"$PYTHON" -m pip install --disable-pip-version-check --upgrade --target "$ROOT/.deps" -r "$ROOT/requirements.txt"
PYTHONPATH="$ROOT/.deps${PYTHONPATH:+:$PYTHONPATH}" "$ROOT/.venv/bin/python" -c 'import rsa, websockets; assert rsa.__version__ == "4.9" and websockets.__version__ == "10.4"'
touch "$ROOT/.install-complete"
chmod +x "$ROOT/start_bridge.sh" "$ROOT/scripts/start.sh" "$ROOT/scripts/stop.sh" "$ROOT/scripts/verify.sh"
echo "安装完成。没有修改厂商目录。"
