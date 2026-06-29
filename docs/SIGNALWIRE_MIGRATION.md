# SignalWire migration (test rig)

Parallel migration path from the current **Twilio Elastic SIP Trunk** test rig toward **SignalWire SIP Domain Apps + SWML**. Twilio stays in place until SignalWire is verified end-to-end.

## Current Twilio architecture

```
GSM gateway phone  --SIP REGISTER/INVITE-->  Twilio SIP domain (safwatly)
                                                    |
                                                    v
                              GET https://sip-webhook.loom.li/twiml  (TwiML)
                                                    |
                                                    v
                              <Dial> +17402185427 </Dial>  (Twilio trunk → OpenAI)
                                                    |
                                                    v
                              sip:proj_...@sip.api.openai.com
                                                    |
                                                    v
                              POST https://sip-webhook.loom.li/  (realtime.call.incoming)
                                                    |
                                                    v
                              bridge-worker accepts call + monitor WebSocket
```

Key pieces:

| Component | Twilio today |
|-----------|--------------|
| SIP ingress for gateway | Elastic SIP Trunk + SIP domain |
| Inbound call handler | TwiML webhook `/twiml` on `bridge-worker` |
| Bridge to OpenAI | `<Dial><Number>+17402185427</Number></Dial>` (trunk number tied to OpenAI) |
| OpenAI webhook | `bridge-worker` `POST /` — **SIP-agnostic, stays** |
| Outbound PSTN test | `/outbound-test` TwiML (plays demo MP3) |
| Recordings | Twilio dual-channel via `/recording` callback |

See `bridge-worker/src/index.js` and `bridge-worker/wrangler.toml`.

## Target SignalWire architecture

```
GSM gateway phone  --SIP REGISTER/INVITE-->  SignalWire SIP Domain App
                                                    |
                                                    v
                              SWML (connect to OpenAI SIP URI)
                                                    |
                                                    v
                              sip:proj_...@sip.api.openai.com;transport=tls
                                                    |
                                                    v
                              POST https://sip-webhook.loom.li/  (unchanged)
                                                    |
                                                    v
                              bridge-worker accepts call + monitor WebSocket
```

**bridge-worker can stay as the OpenAI webhook handler.** SignalWire replaces only the Twilio SIP leg and TwiML/SWML routing layer.

### Twilio → SignalWire mapping

| Twilio | SignalWire equivalent |
|--------|----------------------|
| Elastic SIP Trunk | SIP Domain Application (BYOC inbound) |
| SIP domain + credentials | Domain App FQDN + SIP auth (whitelist gateway IP if needed) |
| TwiML webhook URL | SWML script URL or hosted SWML Resource |
| `<Dial><Number>` to OpenAI trunk | SWML `connect` to `sip:proj_<OPENAI_PROJECT_ID>@sip.api.openai.com;transport=tls` |
| `/outbound-test` TwiML | SWML `play` + `pause`, or Calling API `dial` with inline SWML |
| Call recording | SWML `record_call` before `connect` (see SignalWire LiveKit guide pattern) |
| Twilio CLI / REST | `swsh` + Calling API (`/api/calling/calls`) |

### SWML bridge script (parity with `/twiml`)

Host at `https://sip-webhook.loom.li/swml` (to add) or create a SWML Resource in the SignalWire dashboard:

```yaml
version: 1.0.0
sections:
  main:
    - record_call:
        format: wav
        stereo: true
    - connect:
        to: "sip:proj_Cy8aafQOE2xGPfAh45zFq0VG@sip.api.openai.com;transport=tls"
```

Use the OpenAI project ID from `bridge-worker/wrangler.toml` (`OPENAI_PROJECT_ID`). OpenAI must already have the `realtime.call.incoming` webhook pointed at `https://sip-webhook.loom.li/`.

`answerOnBridge` in Twilio TwiML defers answering until the B-leg connects; SWML `connect` similarly bridges legs — tune with SignalWire `connect` options if ringback leaks appear (same class of issue documented in `bridge-worker/src/index.js`).

## Environment variables

| Variable | Required for swsh/API | Notes |
|----------|----------------------|-------|
| `SIGNALWIRE_SPACE` | Yes | Subdomain only, e.g. `yourspace` from `https://yourspace.signalwire.com` |
| `SIGNALWIRE_PROJECT_ID` | Yes | UUID from Dashboard → API (not OpenAI `proj_...`) |
| `SIGNALWIRE_API_KEY` | Yes* | User-added; repo scripts map to `REST_API_TOKEN` for swsh |
| `REST_API_TOKEN` | Alias | swsh native name; optional if `SIGNALWIRE_API_KEY` is set |
| `PROJECT_ID` | Alias | swsh native name; optional if `SIGNALWIRE_PROJECT_ID` is set |

OpenAI vars (`OPENAI_API_KEY`, `OPENAI_WEBHOOK_SECRET`) remain on the Worker via `wrangler secret put` — unchanged.

Copy `.env.example` → `.env` and fill SignalWire fields. **Quote values that contain shell-special characters** (`#`, `!`, spaces, etc.); `scripts/signalwire/env.sh` parses lines instead of `source .env` for that reason.

### Current status (repo `.env`)

Run `./scripts/signalwire/check-auth.sh` — expects all vars set and `auth: ok (HTTP 200)` on `GET /api/relay/rest/domain_applications`.

**SignalWire space (live):**

| Resource | Status |
|----------|--------|
| Domain Application `gsm-gateway` | **created** — `loomli-gsm-gateway.dapp.signalwire.com` |
| SIP endpoints | 0 |
| Phone numbers | 1 — **+12015029074** ($0.50/mo US local, NJ) |

**SIP domain vs SWML webhook:** The gateway registers/calls to SignalWire's **assigned** Domain App FQDN (`*.dapp.signalwire.com`). The SWML handler URL (`https://sip-webhook.loom.li/swml`) is only where SignalWire fetches call logic — not a custom SIP domain and not entered in the gateway app.

### Worker route (deployed)

`GET` or `POST https://sip-webhook.loom.li/swml` returns SWML JSON:

- `record_call` (wav, stereo) — parity with Twilio dual-channel recording
- `connect` → `sip:proj_<OPENAI_PROJECT_ID>@sip.api.openai.com;transport=tls`

SignalWire Domain Apps fetch SWML via **POST**; GET is for manual `curl` checks.

## Create Domain App via CLI (preferred)

`swsh` can list/create Domain Apps but **does not expose `relay_script` (SWML)** — use the REST API helper instead.

```bash
# One-time: ensure .env has SIGNALWIRE_SPACE, SIGNALWIRE_PROJECT_ID, SIGNALWIRE_API_KEY
./scripts/signalwire/check-auth.sh

# Optional: whitelist gateway public IP (else script uses 0.0.0.0/0 temporarily)
export GATEWAY_PUBLIC_IP="$(adb -s <serial> shell wget -qO- ifconfig.me 2>/dev/null || true)"

./scripts/signalwire/create-domain-app.sh   # idempotent — skips if identifier exists
./scripts/signalwire/list-domain-apps.sh    # confirm FQDN + handler
```

**REST payload** (what the script sends):

```json
{
  "name": "GSM SIP Gateway",
  "identifier": "gsm-gateway",
  "ip_auth_enabled": true,
  "ip_auth": ["<gateway-public-ip>/32"],
  "encryption": "optional",
  "call_handler": "relay_script",
  "call_relay_script_url": "https://sip-webhook.loom.li/swml"
}
```

Endpoint: `POST https://<SIGNALWIRE_SPACE>.signalwire.com/api/relay/rest/domain_applications` with Basic auth `PROJECT_ID:REST_API_TOKEN`.

**Response fields to note:**

| Field | Example | Use |
|-------|---------|-----|
| `domain` | `loomli-gsm-gateway` | Short name — append `.dapp.signalwire.com` for SIP server |
| `id` | UUID | Optional: `SIGNALWIRE_DOMAIN_APP_ID` in `.env` |
| `user` | `*` | Wildcard SIP user — **IP auth is primary** for Domain Apps |
| SIP password | *(not in API)* | Not returned by create/list; Domain Apps use IP whitelist. If digest auth is challenged, check Dashboard → SIP → Domain Apps |

**swsh (read/update only for SWML apps):**

```bash
./scripts/signalwire/swsh-wrap.sh "domain_application list"
./scripts/signalwire/swsh-wrap.sh "domain_application create --help"   # no relay_script option
```

To tighten IP whitelist after testing:

```bash
curl -sS -u "$PROJECT_ID:$REST_API_TOKEN" -H 'Content-Type: application/json' \
  -X PATCH "https://${SIGNALWIRE_SPACE}.signalwire.com/api/relay/rest/domain_applications/${SIGNALWIRE_DOMAIN_APP_ID}" \
  -d '{"ip_auth":["<gateway-ip>/32"]}'
```

## SignalWire dashboard steps (if not using CLI)

1. **API tab** — copy **Space** subdomain, **Project ID**, and create a **REST API token** with voice/SIP scopes as needed.
2. **SIP → Domain Apps → Create** — inbound BYOC domain for the gateway.
   - Whitelist the gateway's public IP (or disable open routing).
   - **Handle using:** SWML Script.
   - **When a call comes in:** `https://sip-webhook.loom.li/swml` (Worker-hosted; SignalWire fetches via POST).
3. **SIP credentials** — Domain Apps authenticate primarily by **IP whitelist** (`user: *`). No password is returned by the API; use dashboard only if digest auth is required.
4. **(Optional) Phone number** — only if PSTN outbound tests like Twilio `/outbound-test` are needed; purchasing incurs cost.
5. **OpenAI** — confirm SIP webhook still targets `sip-webhook.loom.li` and project ID matches SWML `connect` URI.

## Gateway app SIP settings

Point the gateway at SignalWire's **assigned Domain App FQDN** (not `loom.li` — that URL is only the SWML webhook):

| Field | Value |
|-------|-------|
| Server / domain | `loomli-gsm-gateway.dapp.signalwire.com` (from API `domain` + `.dapp.signalwire.com`) |
| Port | `5060` |
| Username | `gateway` (any value when Domain App `user` is `*`) |
| Password | Leave empty unless SignalWire returns `401/407` on REGISTER — then check Dashboard → SIP → Domain Apps |
| Transport | UDP (`encryption: optional` on Domain App; gateway `SipClient` is UDP-only) |

**SWML script URL** (configured on Domain App, not in gateway app):

```
https://sip-webhook.loom.li/swml
```

Tighten IP whitelist from `0.0.0.0/0` to the gateway phone's public IP once known (`GATEWAY_PUBLIC_IP` in create script, or PATCH via REST).

The gateway is SIP-agnostic (`CLAUDE.md`); no app code changes required for peer swap.

## Tooling (Phase 1)

```bash
# One-time (repo root)
curl -LsSf https://astral.sh/uv/install.sh | sh   # if uv missing
uv venv .venv-signalwire --python 3.11
source .venv-signalwire/bin/activate
uv pip install swsh

# Helpers
chmod +x scripts/signalwire/*.sh
./scripts/signalwire/check-auth.sh          # env + REST probe
./scripts/signalwire/create-domain-app.sh   # create/list Domain App (REST; SWML handler)
./scripts/signalwire/list-domain-apps.sh    # JSON summary of Domain Apps
./scripts/signalwire/swsh-wrap.sh "help"    # swsh via venv + .env mapping
```

`.venv-signalwire/` is gitignored.

### swsh env mapping

swsh expects:

```bash
export SIGNALWIRE_SPACE=<subdomain>
export PROJECT_ID=<signalwire-project-uuid>
export REST_API_TOKEN=<api-token>
```

Repo scripts set `PROJECT_ID` and `REST_API_TOKEN` from `SIGNALWIRE_PROJECT_ID` and `SIGNALWIRE_API_KEY`.

## bridge-worker changes

Twilio routes remain; SignalWire runs in parallel:

| Route | Status | Purpose |
|-------|--------|---------|
| `GET/POST /swml` | **deployed** | SWML `record_call` + `connect` to OpenAI SIP (replaces `/twiml` for SignalWire) |
| `GET/POST /outbound-test-swml` | **deployed** | SWML 60s pause for SignalWire outbound PSTN test calls |
| `POST /sw-recording` | planned | Recording status callback (if using Worker-hosted SWML with callbacks) |

No changes to `POST /` OpenAI webhook handler.

## Cost notes

- Domain Apps and SIP endpoints: check SignalWire pricing for your space.
- **US/CA local numbers:** ~$0.50/mo; toll-free ~$0.80/mo ([SignalWire pricing](https://signalwire.com/pricing/messaging)).
- Live test rig number: **+12015029074** (~$0.50/mo). Use `buy-number.sh --search` before purchasing more.
- Trial accounts: outbound calls require the **To** number to be purchased or [verified](#trial-restrictions-verified-numbers).

## Test call procedures (E2E)

Prerequisites:

```bash
./scripts/signalwire/check-auth.sh          # auth ok
adb devices                                 # Pixel 7 connected
md5sum gateway.apk                          # match device priv-app MD5
./deploy.sh --no-build                      # if APK drift
```

Monitor during any call (two terminals):

```bash
adb logcat -s GatewayService:* SipClient:* RtpSession:* CallOrchestrator:*
cd bridge-worker && npx wrangler tail
```

Look for on device: `GSM_RINGING` → `Sent INVITE` → `BRIDGED` → `RTP-STATS` / `capRMS>0`.  
Look in wrangler: `POST /swml` (SignalWire fetches bridge script), then `POST /` with `realtime.call.incoming`.

### Phase 1 — Twilio → GSM gateway → SignalWire Domain App → OpenAI

Uses Twilio trial number **+17402185427** to dial gateway SIM **+16479163598**. Inline TwiML avoids the trial keypress gate on webhook URLs.

```bash
twilio api:core:calls:create \
  --to=+16479163598 \
  --from=+17402185427 \
  --record --recording-channels dual \
  --twiml='<?xml version="1.0"?><Response><Pause length="60"/></Response>'
```

Download recording after call:

```bash
twilio api:core:calls:recordings:list --call-sid=CA...
./scripts/download-recording.sh RE... recordings/twilio-e2e.wav
```

**2026-06-29 result (partial pass):**

| Check | Result |
|-------|--------|
| GSM inbound from Twilio | ✓ `GSM_RINGING` from +17402185427 |
| SIP INVITE to SignalWire | ✓ `Sent INVITE` → `loomli-gsm-gateway.dapp.signalwire.com` |
| SignalWire SWML fetch | ✓ `POST /swml` HTTP 200 (×2, retry INVITE) |
| Gateway BRIDGED | ✓ ~1s then dropped |
| OpenAI webhook | ✗ no `POST /` / `realtime.call.incoming` |
| Twilio recording | ✓ 4s dual-channel WAV |

Call dropped before OpenAI leg connected. Device log shows duplicate SIP 200 / retry INVITE and `onRtpReady ignored — bridgeState=BRIDGED` (RTP SDP arrived after bridge already established). `capRMS=0` on first RTP frames.

### Phase 2 — SignalWire phone number

**Owned number:** +12015029074 (purchased 2026-06-29, ~$0.50/mo).

```bash
./scripts/signalwire/buy-number.sh --search
./scripts/signalwire/buy-number.sh --buy +1... --yes   # requires --yes after reviewing price

# Outbound PSTN test (SignalWire → gateway SIM):
./scripts/signalwire/outbound-call-test.sh --from +12015029074 --to +16479163598
```

Configure inbound handler on a purchased number (SWML bridge, same as Domain App):

```bash
# PATCH via REST (or Dashboard → Phone Numbers → edit):
# call_handler=relay_script, call_relay_script_url=https://sip-webhook.loom.li/swml
```

Self-call smoke test (no gateway, verifies number + SWML routing):

```bash
./scripts/signalwire/outbound-call-test.sh --from +12015029074 --to +12015029074
# wrangler tail: POST /outbound-test-swml (outbound leg) + POST /swml (inbound leg)
```

**2026-06-29 result:**

| Check | Result |
|-------|--------|
| Number purchase | ✓ +12015029074 @ $0.50/mo |
| Outbound → gateway SIM | ✗ trial: To must be verified |
| Self-call API | ✓ Call SID queued/completed |
| SWML endpoints | ✓ `/outbound-test-swml` + `/swml` both fetched |

### Trial restrictions (verified numbers)

SignalWire trial accounts reject outbound calls to unverified **To** numbers:

```
To number is not a purchased or verified number in your Project
```

Verify the gateway SIM before `outbound-call-test.sh` to +16479163598:

```bash
./scripts/signalwire/verify-number.sh +16479163598
# answer verification call on gateway phone, then:
./scripts/signalwire/verify-number.sh --submit <id> <code>
```

Twilio trial symmetrically blocks calls **to** unverified numbers (e.g. new SignalWire DIDs). Verify +12015029074 in Twilio Console if testing Twilio → SignalWire number inbound.

### Scripts

| Script | Purpose |
|--------|---------|
| `scripts/signalwire/buy-number.sh` | Search/purchase DIDs (`--search`, `--buy --yes`) |
| `scripts/signalwire/outbound-call-test.sh` | LaML outbound call with `/outbound-test-swml` hold |
| `scripts/signalwire/verify-number.sh` | Verified caller ID flow for trial outbound |
| `scripts/download-recording.sh` | Twilio dual-channel WAV download |

## Next migration steps

1. ~~Add `SIGNALWIRE_SPACE` and `SIGNALWIRE_PROJECT_ID` to `.env`~~ — done; `./scripts/signalwire/check-auth.sh` reports `auth: ok`.
2. ~~**Create Domain App**~~ — done via `./scripts/signalwire/create-domain-app.sh` → `loomli-gsm-gateway.dapp.signalwire.com`, SWML → `https://sip-webhook.loom.li/swml`.
3. ~~Point gateway SIP settings at the Domain App FQDN~~ — done; gateway registers to SignalWire.
4. ~~Place test call~~ — Twilio path partial (see [Phase 1](#phase-1--twilio--gsm-gateway--signalwire-domain-app--openai)); fix RTP race + reach OpenAI `POST /`.
5. Verify gateway SIM on SignalWire (`verify-number.sh`); re-run `outbound-call-test.sh` for full SignalWire-owned PSTN leg.
6. Add `scripts/signalwire/download-recording.sh` (SignalWire recording API) as Twilio script parallel.
7. Retire Twilio trunk only after SignalWire path matches recording + bidirectional audio evidence.

## References

- [swsh docs](https://signalwire.com/docs/platform/swsh)
- [SIP Domain Applications](https://signalwire.com/docs/platform/voice/sip/domain-applications)
- [Bring your own carrier (BYOC)](https://signalwire.com/docs/platform/voice/sip/bring-your-own-carrier)
- [SWML connect](https://signalwire.com/docs/swml/methods/connect)
- [OpenAI Realtime SIP](https://developers.openai.com/api/docs/guides/realtime-sip)
