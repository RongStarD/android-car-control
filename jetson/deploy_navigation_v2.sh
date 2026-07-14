#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PATCH_DIR="$SCRIPT_DIR/patches"
TARGET_DIR="/tmp"
RESTART=false

usage() {
    cat <<'EOF'
Usage: bash deploy_navigation_v2.sh [--target DIR] [--restart]

Patches app_bridge.py and robot_app_api.py in DIR. Before the first change it
stores an exact backup under DIR/car-app-v2-backups and records that directory
in DIR/.car-app-v2-navigation-backup. Without --restart, running processes are
not touched.
EOF
}

while (($#)); do
    case "$1" in
        --target)
            TARGET_DIR="$2"
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

APP_PATCH="$PATCH_DIR/app_bridge-navigation-v2.patch"
API_PATCH="$PATCH_DIR/robot_app_api-navigation-v2.patch"
APP_TARGET="$TARGET_DIR/app_bridge.py"
API_TARGET="$TARGET_DIR/robot_app_api.py"
MARKER="$TARGET_DIR/.car-app-v2-navigation-backup"

command -v patch >/dev/null || {
    echo "GNU patch is required in the container (apt-get install patch)." >&2
    exit 1
}
[[ -f "$APP_PATCH" ]] || { echo "Missing $APP_PATCH" >&2; exit 1; }
[[ -f "$API_PATCH" ]] || { echo "Missing $API_PATCH" >&2; exit 1; }
[[ -f "$APP_TARGET" ]] || { echo "Missing $APP_TARGET" >&2; exit 1; }
[[ -f "$API_TARGET" ]] || { echo "Missing $API_TARGET" >&2; exit 1; }

patch_state() {
    local patch_file="$1"
    if patch --batch --forward --dry-run -p1 -d "$TARGET_DIR" <"$patch_file" >/dev/null 2>&1; then
        printf 'pending'
    elif patch --batch --reverse --dry-run -p1 -d "$TARGET_DIR" <"$patch_file" >/dev/null 2>&1; then
        printf 'applied'
    else
        printf 'conflict'
    fi
}

APP_STATE="$(patch_state "$APP_PATCH")"
API_STATE="$(patch_state "$API_PATCH")"
echo "Preflight: app_bridge=$APP_STATE, robot_app_api=$API_STATE"

if [[ "$APP_STATE" == conflict || "$API_STATE" == conflict ]]; then
    echo "Patch context does not match the deployed source; no file was changed." >&2
    echo "Compare the Jetson files with the V2 reference before retrying." >&2
    exit 1
fi

if [[ "$APP_STATE" == pending || "$API_STATE" == pending ]]; then
    STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
    BACKUP_DIR="$TARGET_DIR/car-app-v2-backups/$STAMP"
    mkdir -p "$BACKUP_DIR"
    cp -p "$APP_TARGET" "$BACKUP_DIR/app_bridge.py"
    cp -p "$API_TARGET" "$BACKUP_DIR/robot_app_api.py"

    rollback_on_error() {
        local exit_code=$?
        if ((exit_code != 0)); then
            echo "Deployment failed; restoring $BACKUP_DIR" >&2
            cp -p "$BACKUP_DIR/app_bridge.py" "$APP_TARGET"
            cp -p "$BACKUP_DIR/robot_app_api.py" "$API_TARGET"
        fi
        exit "$exit_code"
    }
    trap rollback_on_error EXIT

    if [[ "$APP_STATE" == pending ]]; then
        patch --batch --forward -p1 -d "$TARGET_DIR" <"$APP_PATCH"
    fi
    if [[ "$API_STATE" == pending ]]; then
        patch --batch --forward -p1 -d "$TARGET_DIR" <"$API_PATCH"
    fi
    python3 -m py_compile "$APP_TARGET" "$API_TARGET"
    python3 "$SCRIPT_DIR/verify_navigation_v2_static.py" "$APP_TARGET" "$API_TARGET"
    printf '%s\n' "$BACKUP_DIR" >"$MARKER"

    trap - EXIT
    echo "Patched sources validated. Rollback snapshot: $BACKUP_DIR"
else
    python3 -m py_compile "$APP_TARGET" "$API_TARGET"
    python3 "$SCRIPT_DIR/verify_navigation_v2_static.py" "$APP_TARGET" "$API_TARGET"
    echo "Both V2 patches were already present; no backup was overwritten."
fi

if [[ "$RESTART" == true ]]; then
    bash "$SCRIPT_DIR/restart_v2_runtime.sh" --target "$TARGET_DIR"
else
    echo "Processes were not restarted. Re-run with --restart after review."
fi
