#!/usr/bin/env bash
# Create (or show) a SignalWire SIP Domain Application for the GSM gateway test rig.
# Uses REST API — swsh lacks relay_script (SWML) handler support as of 2026-06.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"
load_signalwire_env

NAME="${SIGNALWIRE_DOMAIN_APP_NAME:-GSM SIP Gateway}"
IDENTIFIER="${SIGNALWIRE_DOMAIN_APP_IDENTIFIER:-gsm-gateway}"
SWML_URL="${SIGNALWIRE_SWML_URL:-https://sip-webhook.loom.li/swml}"
GATEWAY_IP="${GATEWAY_PUBLIC_IP:-}"
ENCRYPTION="${SIGNALWIRE_DOMAIN_ENCRYPTION:-optional}"

for v in SIGNALWIRE_SPACE PROJECT_ID REST_API_TOKEN; do
  if [[ -z "${!v:-}" ]]; then
    echo "Missing $v — set in .env (see .env.example)." >&2
    exit 1
  fi
done

host="${SIGNALWIRE_SPACE}.signalwire.com"
base_url="https://${host}/api/relay/rest/domain_applications"

if [[ -z "$GATEWAY_IP" ]]; then
  echo "GATEWAY_PUBLIC_IP not set — using 0.0.0.0/0 (tighten after first successful call)." >&2
  ip_auth='["0.0.0.0/0"]'
else
  ip_auth="[\"${GATEWAY_IP}/32\"]"
fi

existing_id=""
existing_json="$(curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Accept: application/json' "$base_url")"
existing_id="$(echo "$existing_json" | python3 -c "
import json, sys
raw = json.load(sys.stdin)
items = raw.get('data', raw) if isinstance(raw, dict) else raw
if isinstance(items, dict) and 'data' in items:
    items = items['data']
if not isinstance(items, list):
    items = [items]
ident = sys.argv[1]
for app in items:
    if app.get('identifier') == ident:
        print(app.get('id', ''))
        break
" "$IDENTIFIER" 2>/dev/null || true)"

if [[ -n "$existing_id" ]]; then
  echo "Domain Application already exists (identifier=${IDENTIFIER}, id=${existing_id})"
  resp="$(curl -sS -u "${PROJECT_ID}:${REST_API_TOKEN}" -H 'Accept: application/json' "${base_url}/${existing_id}")"
else
  payload="$(python3 -c "
import json
print(json.dumps({
    'name': '''$NAME''',
    'identifier': '''$IDENTIFIER''',
    'ip_auth_enabled': True,
    'ip_auth': json.loads('''$ip_auth'''),
    'encryption': '''$ENCRYPTION''',
    'call_handler': 'relay_script',
    'call_relay_script_url': '''$SWML_URL''',
}))
")"
  http_out="$(curl -sS -w $'\n%{http_code}' -u "${PROJECT_ID}:${REST_API_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$payload" "$base_url")"
  resp="$(echo "$http_out" | head -n -1)"
  code="$(echo "$http_out" | tail -n 1)"
  if [[ "$code" != "201" ]]; then
    echo "Create failed (HTTP $code):" >&2
    echo "$resp" >&2
    exit 1
  fi
  echo "Created Domain Application (HTTP 201)"
fi

echo "$resp" | python3 -c "
import json, sys
app = json.load(sys.stdin)
domain = app.get('domain', '')
fqdn = f'{domain}.dapp.signalwire.com' if domain and '.dapp.' not in domain else domain
print('')
print('=== SignalWire Domain Application ===')
print(f\"ID:         {app.get('id')}\")
print(f\"Name:       {app.get('name')}\")
print(f\"Identifier: {app.get('identifier')}\")
print(f\"SIP FQDN:   {fqdn}\")
print(f\"SIP user:   {app.get('user')}  (wildcard — IP auth is primary)\")
print(f\"Handler:    {app.get('call_handler')}\")
print(f\"SWML URL:   {app.get('call_relay_script_url')}\")
print(f\"IP auth:    {app.get('ip_auth_enabled')} {app.get('ip_auth')}\")
print(f\"Encryption: {app.get('encryption')}\")
print('')
print('Gateway app settings (Settings → SIP):')
print(f\"  Server:    {fqdn}\")
print('  Port:      5060')
print('  Username:  gateway  (any value when user is \"*\")')
print('  Password:  leave empty unless SignalWire challenges digest auth')
print('  Transport: UDP (encryption optional on Domain App)')
print('')
print('Store SIGNALWIRE_DOMAIN_APP_ID in .env (optional). No SIP password is returned by the API.')
print('If digest auth is required, check Dashboard → SIP → Domain Apps → credentials section.')
"
