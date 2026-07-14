#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="/tmp"
BACKUP_DIR=""
RESTART=false

usage() {
    cat <<'EOF'
Usage: bash rollback_navigation_v2.sh [--target DIR] [--backup DIR] [--restart]

Without --backup, restores the snapshot recorded in
DIR/.car-app-v2-navigation-backup. Without --restart, running processes are not
touched.
EOF
}

while (($#)); do
    case "$1" in
        --target)
            TARGET_DIR="$2"
            shift 2
            ;;
        --backup)
            BACKUP_DIR="$2"
            shift 2
            ;;
        --restart)
            RESTART=true
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

MARKER="$TARGET_DIR/.car-app-v2-navigation-backup"
if [[ -z "$BACKUP_DIR" ]]; then
    [[ -f "$MARKER" ]] || { echo "Missing rollback marker: $MARKER" >&2; exit 1; }
    BACKUP_DIR="$(head -n 1 "$MARKER")"
fi

[[ -f "$BACKUP_DIR/app_bridge.py" ]] || { echo "Missing backup app_bridge.py" >&2; exit 1; }
[[ -f "$BACKUP_DIR/robot_app_api.py" ]] || { echo "Missing backup robot_app_api.py" >&2; exit 1; }

cp -p "$BACKUP_DIR/app_bridge.py" "$TARGET_DIR/app_bridge.py"
cp -p "$BACKUP_DIR/robot_app_api.py" "$TARGET_DIR/robot_app_api.py"
python3 -m py_compile "$TARGET_DIR/app_bridge.py" "$TARGET_DIR/robot_app_api.py"
rm -f "$MARKER"

echo "Restored Jetson sources from $BACKUP_DIR"
if [[ "$RESTART" == true ]]; then
    bash "$SCRIPT_DIR/restart_v2_runtime.sh" --target "$TARGET_DIR"
else
    echo "Processes were not restarted. Use --restart to activate the restored files."
fi

