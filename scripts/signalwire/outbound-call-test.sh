#!/usr/bin/env bash
# Place an outbound PSTN call from a SignalWire number to the gateway SIM.
# Keeps the A-leg up via SWML pause (same idea as Twilio inline Pause TwiML).
#
# Usage:
#   ./scripts/signalwire/outbound-call-test.sh --from +12015029073 --to +16479163598
#   ./scripts/signalwire/outbound-call-test.sh   # uses first owned number + GATEWAY_SIM from .env
#
# Monitor during call:
#   adb logcat -s GatewayService:* SipClient:*
#   cd bridge-worker && npx wrangler tail
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
load_signalwire_env

FROM_NUM=""
TO_NUM="${GATEWAY_SIM:-+16479163598}"
SWML_URL="${SIGNALWIRE_OUTBOUND_SWML_URL:-https://sip-webhook.loom.li/outbound-test-swml}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --from) FROM_NUM="${2:?}"; shift 2 ;;
    --to) TO_NUM="${2:?}"; shift 2 ;;
    --url) SWML_URL="${2:?}"; shift 2 ;;
    -h|--help)
      sed -n '2,11p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

for v in SIGNALWIRE_SPACE PROJECT_ID REST_API_TOKEN; do
  if [[ -z "${!v:-}" ]]; then
    echo "Missing $v — set in .env." >&2
    exit 1
  fi
done

host="${SIGNALWIRE_SPACE}.signalwire.com"

if [[ -z "$FROM_NUM" ]]; then
  FROM_NUM="$(curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Accept: application/json' \
    "https://${host}/api/relay/rest/phone_numbers" \
    | python3 -c "
import json, sys
raw = json.load(sys.stdin)
items = raw.get('data') or []
if not items:
    sys.exit(1)
print(items[0].get('number') or items[0].get('e164', ''))
" 2>/dev/null || true)"
fi

if [[ -z "$FROM_NUM" ]]; then
  echo "No --from number and no owned SignalWire numbers — run buy-number.sh first." >&2
  exit 1
fi

# LaML-compatible outbound API (SignalWire Calling API / swsh send_call use the same stack).
calls_url="https://${host}/api/laml/2010-04-01/Accounts/${PROJECT_ID}/Calls.json"
echo "Placing call: $FROM_NUM → $TO_NUM (Url=$SWML_URL)"

resp="$(curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" \
  -X POST "$calls_url" \
  --data-urlencode "From=${FROM_NUM}" \
  --data-urlencode "To=${TO_NUM}" \
  --data-urlencode "Url=${SWML_URL}")"

echo "$resp" | python3 -c "
import json, sys
d = json.load(sys.stdin)
if d.get('message') and not d.get('sid'):
    print('Error:', d.get('message'), file=sys.stderr)
    sys.exit(1)
print('Call SID:', d.get('sid'))
print('Status:  ', d.get('status'))
print('From:    ', d.get('from'))
print('To:      ', d.get('to'))
"
