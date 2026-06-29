#!/usr/bin/env bash
# Run swsh from the repo venv with .env credentials mapped to swsh variable names.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VENV="$ROOT/.venv-signalwire"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

if [[ ! -x "$VENV/bin/swsh" ]]; then
  echo "swsh not installed. Run: uv venv .venv-signalwire --python 3.11 && uv pip install swsh" >&2
  exit 1
fi

load_signalwire_env

exec "$VENV/bin/swsh" "$@"
