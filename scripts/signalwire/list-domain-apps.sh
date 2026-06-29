#!/usr/bin/env bash
# List SignalWire Domain Applications (safe fields only — no secrets).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/swsh-wrap.sh" domain_application list -j | python3 -c "
import json, sys
raw = json.load(sys.stdin)
items = raw if isinstance(raw, list) else raw.get('data', [raw])
for app in items:
    domain = app.get('domain', '')
    fqdn = f'{domain}.dapp.signalwire.com' if domain and '.dapp.' not in domain else domain
    safe = {
        'id': app.get('id'),
        'name': app.get('name'),
        'identifier': app.get('identifier'),
        'sip_fqdn': fqdn,
        'user': app.get('user'),
        'call_handler': app.get('call_handler'),
        'call_relay_script_url': app.get('call_relay_script_url'),
        'ip_auth_enabled': app.get('ip_auth_enabled'),
        'ip_auth': app.get('ip_auth'),
        'encryption': app.get('encryption'),
    }
    print(json.dumps(safe, indent=2))
    print('---')
"
