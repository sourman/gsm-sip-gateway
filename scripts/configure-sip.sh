#!/bin/bash
#
# Push SIP credentials into the gateway app's SharedPreferences via adb root.
# Defaults target the SignalWire test rig (IP auth — empty password).
#
# Usage:
#   ./scripts/configure-sip.sh              # write defaults if prefs missing
#   ./scripts/configure-sip.sh --force      # overwrite existing prefs
#   ./scripts/configure-sip.sh -s SERIAL
#
# Override defaults with env vars (password rarely needed — SignalWire uses IP auth):
#   SIP_SERVER=other.dapp.signalwire.com SIP_USER=gateway ./scripts/configure-sip.sh --force
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PKG="com.callagent.gateway"
PREFS_DIR="/data/data/${PKG}/shared_prefs"
PREFS_FILE="${PREFS_DIR}/gateway.xml"
DEVICE_TMP="/data/local/tmp/gateway-sip-prefs.xml"

SIP_SERVER="${SIP_SERVER:-loomli-gsm-gateway.dapp.signalwire.com}"
SIP_PORT="${SIP_PORT:-5060}"
SIP_USER="${SIP_USER:-gateway}"
SIP_PASS="${SIP_PASS:-}"
SIP_LOCAL_SERVER="${SIP_LOCAL_SERVER:-false}"
SIP_AUTOCONNECT="${SIP_AUTOCONNECT:-true}"

FORCE=false
ADB_SERIAL=""

while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=true ;;
        -s)
            shift
            [ $# -gt 0 ] || { echo "ERROR: -s requires a device serial"; exit 1; }
            ADB_SERIAL="$1"
            ;;
        -h|--help)
            sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1"
            exit 1
            ;;
    esac
    shift
done

adb_cmd() {
    if [ -n "$ADB_SERIAL" ]; then
        adb -s "$ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

su_cmd() {
    adb_cmd shell su -mm -c "$1"
}

fail() {
    echo "FAIL: $*"
    exit 1
}

ok() {
    echo "OK: $*"
}

if ! command -v adb &>/dev/null; then
    fail "adb not found in PATH"
fi

if ! su_cmd "id" 2>/dev/null | grep -q "uid=0"; then
    fail "device root (su) required to write SharedPreferences"
fi

if [ "$FORCE" = false ] && su_cmd "test -f '$PREFS_FILE'" 2>/dev/null; then
    echo "SharedPreferences already exist — skipping (use --force to overwrite)"
    su_cmd "grep -E 'name=\"(server|user)\"' '$PREFS_FILE' 2>/dev/null" || true
    exit 0
fi

local_server_bool="false"
[ "$SIP_LOCAL_SERVER" = "true" ] && local_server_bool="true"
autoconnect_bool="true"
[ "$SIP_AUTOCONNECT" = "false" ] && autoconnect_bool="false"

host_tmp=$(mktemp)
trap 'rm -f "$host_tmp"' EXIT

cat > "$host_tmp" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="server">${SIP_SERVER}</string>
    <int name="port" value="${SIP_PORT}" />
    <string name="user">${SIP_USER}</string>
    <string name="pass">${SIP_PASS}</string>
    <boolean name="local_server" value="${local_server_bool}" />
    <boolean name="autoconnect" value="${autoconnect_bool}" />
</map>
EOF

su_cmd "mkdir -p '$PREFS_DIR'"
adb_cmd push "$host_tmp" "$DEVICE_TMP" >/dev/null
su_cmd "cp '$DEVICE_TMP' '$PREFS_FILE'"

owner=$(su_cmd "ls -ld '$PREFS_DIR'" 2>/dev/null | awk '{print $3":"$4}' || true)
if [ -n "$owner" ] && [ "$owner" != ":" ]; then
    su_cmd "chown '$owner' '$PREFS_FILE'" 2>/dev/null || true
fi
su_cmd "rm -f '$DEVICE_TMP'" 2>/dev/null || true

ok "SIP prefs written: ${SIP_USER}@${SIP_SERVER}:${SIP_PORT} (autoconnect=${autoconnect_bool})"
