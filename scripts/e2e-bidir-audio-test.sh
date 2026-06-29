#!/usr/bin/env bash
# E2E bidirectional audio test: Twilio Say → gateway + host OpenAI WS monitor.
# OpenAI call_id sideband requires Bearer auth (scripts/monitor-openai-call.mjs).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="${BIDIR_LOG:-/tmp/bidir-audio-iteration.log}"
WRANGLER_LOG="/tmp/bidir-wrangler-e2e.txt"
RECORDINGS="$ROOT/recordings"
PAUSE_SEC="${PAUSE_SEC:-55}"

mkdir -p "$RECORDINGS"
: > "$WRANGLER_LOG"

echo "=== E2E $(date -Iseconds) Twilio Say + host WS monitor ===" | tee -a "$LOG"

(cd "$ROOT/bridge-worker" && npx wrangler tail 2>&1 | tee "$WRANGLER_LOG") &
TAIL_PID=$!
sleep 2

adb logcat -c 2>/dev/null || true

CALL_SID="$(twilio api:core:calls:create \
  --to=+16479163598 \
  --from=+17402185427 \
  --record --recording-channels=dual \
  --time-limit=120 \
  --twiml="<?xml version=\"1.0\"?><Response><Say voice=\"alice\">Bidirectional audio test. The quick brown fox jumps over the lazy dog. One two three four five six seven eight nine ten.</Say><Pause length=\"${PAUSE_SEC}\"/></Response>" \
  | grep -oE 'CA[a-f0-9]{32}' | head -1)"

echo "Twilio CallSid=$CALL_SID" | tee -a "$LOG"

CALL_ID=""
for _ in $(seq 1 60); do
  CALL_ID="$(curl -sf "https://sip-webhook.loom.li/last-call-id" 2>/dev/null \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('call_id',''))" 2>/dev/null || true)"
  [[ -n "$CALL_ID" ]] && break
  sleep 1
done
echo "OpenAI call_id=$CALL_ID" | tee -a "$LOG"

if [[ -n "$CALL_ID" ]]; then
  timeout 90 node "$ROOT/scripts/monitor-openai-call.mjs" "$CALL_ID" 2>&1 | tee -a "$LOG" &
  MON_PID=$!
else
  echo "WARN: no call_id from wrangler tail" | tee -a "$LOG"
  MON_PID=""
fi

sleep "$((PAUSE_SEC + 15))"
kill "$TAIL_PID" 2>/dev/null || true
[[ -n "$MON_PID" ]] && kill "$MON_PID" 2>/dev/null || true

DUR="$(twilio api:core:calls:fetch --sid="$CALL_SID" --properties=duration 2>/dev/null | tail -1 | tr -d ' ')"
echo "Twilio duration=${DUR}s" | tee -a "$LOG"

adb logcat -d -s RtpSession:* CallOrchestrator:* 2>/dev/null \
  | grep -E "RTP-STATS|BRIDGED|GSM call ended" | tail -8 | tee -a "$LOG"

RE="$(twilio api:core:calls:recordings:list --call-sid="$CALL_SID" 2>/dev/null | grep -oE 'RE[a-f0-9]{32}' | head -1)"
if [[ -n "$RE" ]]; then
  OUT="$RECORDINGS/e2e-${RE}.wav"
  "$ROOT/scripts/download-recording.sh" "$RE" "$OUT"
  python3 - "$OUT" <<'PY' | tee -a "$LOG"
import struct, sys, wave, math
path = sys.argv[1]
with wave.open(path) as w:
    ch = w.getnchannels()
    rate = w.getframerate()
    raw = w.readframes(w.getnframes())
    samples = struct.unpack("<" + "h" * (len(raw) // 2), raw)
    per = len(samples) // ch
    print(f"recording {path}: {per/rate:.1f}s ch={ch}")
    for c in range(ch):
        chs = samples[c::ch]
        win = rate
        rl = [math.sqrt(sum(x*x for x in chs[i:i+win])/win) for i in range(0, len(chs)-win, win)]
        o = math.sqrt(sum(x*x for x in chs)/len(chs))
        sw = sum(1 for r in rl if r > 500)
        print(f"  Ch{c+1}: RMS={o:.0f} speech_windows={sw}/{len(rl)}")
PY
fi

echo "Done. See $LOG"
