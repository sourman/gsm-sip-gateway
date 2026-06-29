#!/usr/bin/env bash
# Load SignalWire-related vars from repo .env without printing secret values.
# Parses KEY=value lines only (avoids bash `source` issues with special chars in values).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

load_signalwire_env() {
  local env_file="$ROOT/.env"
  [[ -f "$env_file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^(SIGNALWIRE_[A-Za-z0-9_]+|PROJECT_ID|REST_API_TOKEN)= ]] || continue
    export "$line"
  done < "$env_file"

  # swsh expects subdomain only (e.g. "loomli"), not FQDN
  local space="${SIGNALWIRE_SPACE:-}"
  space="${space%.signalwire.com}"
  export SIGNALWIRE_SPACE="$space"
  export PROJECT_ID="${PROJECT_ID:-${SIGNALWIRE_PROJECT_ID:-}}"
  export REST_API_TOKEN="${REST_API_TOKEN:-${SIGNALWIRE_API_TOKEN:-${SIGNALWIRE_API_KEY:-}}}"
}

var_status() {
  local name="$1"
  if [[ -n "${!name:-}" ]]; then
    echo "$name: set"
  else
    echo "$name: missing"
  fi
}
