#!/usr/bin/env bash
# Start SignalWire verified-caller-ID flow (required for trial outbound to non-owned numbers).
#
# Usage:
#   ./scripts/signalwire/verify-number.sh +16479163598          # triggers verification call
#   ./scripts/signalwire/verify-number.sh --submit ID CODE      # complete after answering
#   ./scripts/signalwire/verify-number.sh --list
#
# Trial accounts can only dial purchased or verified To numbers. Verify the gateway SIM
# before outbound-call-test.sh to +16479163598 will work.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
load_signalwire_env

host="${SIGNALWIRE_SPACE}.signalwire.com"
base="https://${host}/api/relay/rest/verified_caller_ids"

for v in SIGNALWIRE_SPACE PROJECT_ID REST_API_TOKEN; do
  if [[ -z "${!v:-}" ]]; then
    echo "Missing $v — set in .env." >&2
    exit 1
  fi
done

ACTION="create"
PHONE=""
VC_ID=""
CODE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list) ACTION="list"; shift ;;
    --submit) ACTION="submit"; VC_ID="${2:?}"; CODE="${3:?}"; shift 3 ;;
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    +*) PHONE="$1"; shift ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ "$ACTION" == "list" ]]; then
  curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Accept: application/json' "$base" \
    | python3 -c "
import json, sys
raw = json.load(sys.stdin)
for v in raw.get('data') or []:
    print(v.get('id'), v.get('phone_number'), 'verified=' + str(v.get('verified')))
"
  exit 0
fi

if [[ "$ACTION" == "submit" ]]; then
  payload="$(python3 -c "import json; print(json.dumps({'verification_code': '''$CODE'''}))")"
  curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Content-Type: application/json' \
    -X POST "${base}/${VC_ID}/verify" -d "$payload" \
    | python3 -m json.tool
  exit 0
fi

if [[ -z "$PHONE" ]]; then
  echo "E.164 phone number required (e.g. +16479163598)." >&2
  exit 1
fi

payload="$(python3 -c "import json; print(json.dumps({'phone_number': '''$PHONE'''}))")"
resp="$(curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Content-Type: application/json' \
  -X POST "$base" -d "$payload")"

echo "$resp" | python3 -c "
import json, sys
v = json.load(sys.stdin)
print('Verification initiated for', v.get('phone_number'))
print('ID:', v.get('id'))
print('Verified:', v.get('verified'))
print('')
print('Answer the verification call on the phone, note the code, then:')
print('  ./scripts/signalwire/verify-number.sh --submit', v.get('id'), '<code>')
"
