#!/usr/bin/env bash
# Search and optionally purchase a SignalWire phone number for PSTN test calls.
#
# Usage:
#   ./scripts/signalwire/buy-number.sh --search              # list available US local numbers
#   ./scripts/signalwire/buy-number.sh --search --areacode 647 --country CA
#   ./scripts/signalwire/buy-number.sh --buy +12015029073    # purchase (requires --yes)
#   ./scripts/signalwire/buy-number.sh --buy --yes           # buy first search result
#
# Pricing (SignalWire public rate card, 2026-06): US/CA local ~$0.50/mo, toll-free ~$0.80/mo.
# Script refuses --buy without --yes so cost is explicit before purchase.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
load_signalwire_env

COUNTRY="${SIGNALWIRE_NUMBER_COUNTRY:-US}"
AREACODE=""
NUMBER_TYPE="local"
ACTION="search"
BUY_NUMBER=""
CONFIRM=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --search) ACTION="search"; shift ;;
    --buy) ACTION="buy"; shift ;;
    --yes) CONFIRM=1; shift ;;
    --country) COUNTRY="${2:?}"; shift 2 ;;
    --areacode) AREACODE="${2:?}"; shift 2 ;;
    --toll-free) NUMBER_TYPE="toll-free"; shift ;;
    +*) BUY_NUMBER="$1"; shift ;;
    -h|--help)
      sed -n '2,12p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

for v in SIGNALWIRE_SPACE PROJECT_ID REST_API_TOKEN; do
  if [[ -z "${!v:-}" ]]; then
    echo "Missing $v — set in .env (see .env.example)." >&2
    exit 1
  fi
done

host="${SIGNALWIRE_SPACE}.signalwire.com"
base="https://${host}/api/relay/rest/phone_numbers"

monthly_usd="0.50"
if [[ "$NUMBER_TYPE" == "toll-free" ]]; then
  monthly_usd="0.80"
fi

search_params="country_code=${COUNTRY}&number_type=${NUMBER_TYPE}&limit=10"
[[ -n "$AREACODE" ]] && search_params="${search_params}&areacode=${AREACODE}"

search_json="$(curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Accept: application/json' \
  "${base}/search?${search_params}")"

if [[ "$ACTION" == "search" ]]; then
  echo "$search_json" | python3 -c "
import json, sys
raw = json.load(sys.stdin)
items = raw.get('data') or []
if not items:
    print('No numbers found.')
    sys.exit(1)
print(f'Available {sys.argv[1]} {sys.argv[2]} numbers (~\${sys.argv[3]}/mo):')
for n in items[:10]:
    e164 = n.get('e164') or n.get('number', '')
    region = n.get('region') or ''
    rc = n.get('rate_center') or ''
    caps = ','.join(n.get('capabilities') or [])
    print(f'  {e164}  {region} {rc}  [{caps}]')
" "$COUNTRY" "$NUMBER_TYPE" "$monthly_usd"
  exit 0
fi

if [[ -z "$BUY_NUMBER" ]]; then
  BUY_NUMBER="$(echo "$search_json" | python3 -c "
import json, sys
raw = json.load(sys.stdin)
items = raw.get('data') or []
if not items:
    sys.exit(1)
print(items[0].get('e164') or items[0].get('number', ''))
" 2>/dev/null || true)"
fi

if [[ -z "$BUY_NUMBER" ]]; then
  echo "No number to buy — run --search first or pass E.164 (+1...)." >&2
  exit 1
fi

echo "Purchase candidate: $BUY_NUMBER (${NUMBER_TYPE}, ~\$${monthly_usd}/mo recurring)"
if [[ -z "$CONFIRM" ]]; then
  echo "Refusing purchase without --yes (explicit cost approval)." >&2
  exit 1
fi

payload="$(python3 -c "import json; print(json.dumps({'number': '''$BUY_NUMBER'''}))")"
http_out="$(curl -sS -w $'\n%{http_code}' -u "${PROJECT_ID}:${REST_API_TOKEN}" \
  -H 'Content-Type: application/json' \
  -d "$payload" "$base")"
resp="$(echo "$http_out" | head -n -1)"
code="$(echo "$http_out" | tail -n 1)"

if [[ "$code" != "201" && "$code" != "200" ]]; then
  echo "Purchase failed (HTTP $code):" >&2
  echo "$resp" >&2
  exit 1
fi

echo "$resp" | python3 -c "
import json, sys
n = json.load(sys.stdin)
print('')
print('=== Purchased SignalWire number ===')
print(f\"ID:     {n.get('id')}\")
print(f\"Number: {n.get('number') or n.get('e164')}\")
print(f\"Type:   {n.get('number_type')}\")
print(f\"Region: {n.get('region')}\")
print('')
print('Outbound test to gateway SIM:')
print('  ./scripts/signalwire/outbound-call-test.sh --from', n.get('number') or n.get('e164'))
"
