#!/usr/bin/env bash
# Verify SignalWire env vars (names only) and probe REST API auth.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

load_signalwire_env

echo "=== SignalWire env (set/missing only) ==="
for v in SIGNALWIRE_SPACE SIGNALWIRE_PROJECT_ID PROJECT_ID SIGNALWIRE_API_KEY REST_API_TOKEN; do
  var_status "$v"
done

missing=0
for v in SIGNALWIRE_SPACE PROJECT_ID REST_API_TOKEN; do
  [[ -n "${!v:-}" ]] || missing=1
done

if [[ "$missing" -ne 0 ]]; then
  echo ""
  echo "Cannot probe API: need SIGNALWIRE_SPACE, SIGNALWIRE_PROJECT_ID (or PROJECT_ID), and REST_API_TOKEN (or SIGNALWIRE_API_KEY)."
  echo "Dashboard: https://<space>.signalwire.com → API tab."
  exit 1
fi

host="${SIGNALWIRE_SPACE%.signalwire.com}.signalwire.com"
url="https://${host}/api/relay/rest/endpoints"

echo ""
echo "=== REST probe (GET /api/relay/rest/endpoints) ==="
http_code="$(curl -sS -o /dev/null -w '%{http_code}' \
  -u "${PROJECT_ID}:${REST_API_TOKEN}" \
  -H 'Accept: application/json' \
  "$url" || echo "000")"

case "$http_code" in
  200) echo "auth: ok (HTTP 200)" ;;
  401|403) echo "auth: failed (HTTP $http_code) — check PROJECT_ID and token scopes" ;;
  *) echo "auth: unexpected (HTTP $http_code)" ;;
esac
