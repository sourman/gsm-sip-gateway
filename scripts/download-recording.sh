#!/usr/bin/env bash
# Download a Twilio call recording (dual-channel WAV) for local analysis.
#
# Usage:
#   ./scripts/download-recording.sh RExxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
#   ./scripts/download-recording.sh RE... recordings/my-call.wav
#   ./scripts/download-recording.sh --list 5    # last 5 recordings
#
# Auth (first match wins):
#   1. TWILIO_ACCOUNT_SID + TWILIO_AUTH_TOKEN in repo .env
#   2. Twilio CLI profile (~/.twilio-cli/config.json, default profile gsm2sip)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/recordings"
PROFILE="${TWILIO_PROFILE:-gsm2sip}"

load_env() {
  local env_file="$ROOT/.env"
  [[ -f "$env_file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^TWILIO_[A-Za-z0-9_]+= ]] || continue
    export "$line"
  done < "$env_file"
}

twilio_cli_creds() {
  local cfg="${TWILIO_CONFIG:-$HOME/.twilio-cli/config.json}"
  [[ -f "$cfg" ]] || return 1
  python3 - "$cfg" "$PROFILE" <<'PY'
import json, sys
cfg, profile = sys.argv[1], sys.argv[2]
c = json.load(open(cfg))
profiles = c.get("profiles", {})
p = profiles.get(profile) or profiles.get(c.get("activeProject", "")) or next(iter(profiles.values()))
print(p["accountSid"])
print(p["apiKey"])
print(p["apiSecret"])
PY
}

auth_user() {
  load_env
  if [[ -n "${TWILIO_ACCOUNT_SID:-}" && -n "${TWILIO_AUTH_TOKEN:-}" ]]; then
    echo "${TWILIO_ACCOUNT_SID}:${TWILIO_AUTH_TOKEN}"
    return
  fi
  local lines sid key secret
  lines="$(twilio_cli_creds)" || return 1
  sid="$(sed -n '1p' <<<"$lines")"
  key="$(sed -n '2p' <<<"$lines")"
  secret="$(sed -n '3p' <<<"$lines")"
  export TWILIO_ACCOUNT_SID="$sid"
  echo "${key}:${secret}"
}

account_sid() {
  load_env
  if [[ -n "${TWILIO_ACCOUNT_SID:-}" ]]; then
    echo "$TWILIO_ACCOUNT_SID"
    return
  fi
  twilio_cli_creds | sed -n '1p'
}

if [[ "${1:-}" == "--list" ]]; then
  limit="${2:-10}"
  creds="$(auth_user)" || { echo "No Twilio credentials — set .env or twilio-cli profile" >&2; exit 1; }
  sid="$(account_sid)"
  curl -sf -u "$creds" \
    "https://api.twilio.com/2010-04-01/Accounts/${sid}/Recordings.json?PageSize=${limit}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); 
for r in d.get('recordings',[]): print(r['sid'], r.get('duration','?')+'s', r.get('date_created',''))"
  exit 0
fi

SID="${1:?Recording SID (RE...) required — or use --list}"
OUT="${2:-$OUT_DIR/${SID}.wav}"
mkdir -p "$(dirname "$OUT")"

creds="$(auth_user)" || {
  cat >&2 <<'EOF'
Need Twilio REST credentials. Either:
  • Add to .env:
      TWILIO_ACCOUNT_SID=AC...
      TWILIO_AUTH_TOKEN=...   (from console.twilio.com → Account)
  • Or configure twilio-cli: twilio profiles:use gsm2sip
EOF
  exit 1
}
ACCOUNT="$(account_sid)"
URL="https://api.twilio.com/2010-04-01/Accounts/${ACCOUNT}/Recordings/${SID}.wav"

echo "Downloading $SID → $OUT"
curl -sf -u "$creds" "$URL" -o "$OUT"
ls -lh "$OUT"
