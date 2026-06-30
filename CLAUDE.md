# GSM-SIP Gateway

Bridges GSM call audio (from the local SIM) to a SIP endpoint. Upstream: https://github.com/rmeehub/gsm-sip-gateway

The gateway is **SIP-agnostic**: it speaks SIP/RTP to whatever peer is configured (server + credentials in the app's settings). Whatever lives downstream of that SIP leg is open and pluggable — Asterisk, FreeSWITCH, OpenAI Realtime, anything that speaks SIP/RTP. The current downstream test rig is a Twilio SIP trunk → OpenAI Realtime (see [Test rig](#test-rig-bridge-worker)); that is *not* the product architecture, just the verification harness.

## Terminology

- **Gateway phone** — the Android device running this app (holds the SIM, bridges calls between the calling phone and the SIP side).
- **Calling phone** — the remote party on the GSM leg (whoever the SIM is talking to — inbound caller or outbound callee).
- **SIP side** — the configured SIP peer on the VoIP leg (server + username + password in settings). Not assumed to be Asterisk.
- **Modem RX / downlink / INCALL_RX** — audio coming FROM the calling phone; captured and sent to the SIP side.
- **Modem TX / uplink / INCALL_TX** — audio going TO the calling phone; the SIP side's audio is injected here.
- **Mic isolation** — the gateway must be a pure relay; its physical mic must never be audible on either leg. **Solved** — `MicIsolationGuard` (mutes the mic via mixer ops, then measures capture RMS with playback silent and refuses to enter BRIDGED if it leaks). Verified live: `mic=false` in RTP-STATS, `echo=0`.

## Build & Install

Prereq: run `sudo ./setup.sh` once to install a JDK 17+ and the Android SDK (auto-detects `openjdk-21-jdk` on Debian 13/trixie, else `openjdk-17-jdk`).

```bash
./build.sh          # debug
./build.sh release  # release → outputs gateway-magisk.zip
```

Install the Magisk module (`gateway-magisk.zip`) via Magisk Manager, reboot, set as default phone app.

**Release APK must be signed.** An unsigned release APK is silently rejected by Android's PackageManager when installed as a system priv-app — the package gets wiped on every reboot with `System package com.callagent.gateway no longer exists; its data will be wiped`. `build.sh` signs the release APK with the debug keystore, and `app/build.gradle.kts` has a `signingConfigs.release` block wired to the `release` build type. If you bypass `build.sh`, sign with apksigner (`--ks ~/.android/debug.keystore`, alias `androiddebugkey`, pass `android`) before packaging the module.

## Redeploying after a code change

The app is installed as a Magisk **system priv-app**, so `adb install gateway.apk` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch).

**Primary dev loop — hot-deploy (no reboot):** copy the new APK into the live module overlay and restart the service:

```bash
./deploy.sh              # build release + hot-swap + configure SignalWire SIP
./deploy.sh --no-build   # skip build when gateway.apk already exists
./deploy.sh --no-sip     # skip SharedPreferences SIP configure step
```

`deploy.sh` pushes to `/data/adb/modules/sip-gsm-gateway/system/priv-app/Gateway/Gateway.apk`, verifies MD5 against `/system/priv-app/Gateway/Gateway.apk`, writes SIP prefs via `scripts/configure-sip.sh`, force-stops the app, and root-launches the `MicCapabilityGuard` foreground trampoline to restart `GatewayService`.

**Full module reinstall + reboot** — only when module structure or privapp-permissions changed (new permissions, manifest paths, tinymix binary, etc.):

```bash
./deploy.sh --reboot
# equivalent manual path:
adb push gateway-magisk.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/gateway-magisk.zip"
adb reboot
```

`versionCode` is not bumped across commits, so verify the new code is live by md5, not by the on-device version:

```bash
md5sum gateway.apk                                             # host
adb shell su -c "md5sum /system/priv-app/Gateway/Gateway.apk"   # must match
```

**Credentials survive module reinstalls + reboots, but NOT a `/data/app` uninstall.** SIP credentials are stored in the app's `"gateway"` `SharedPreferences` (Settings UI, or `scripts/configure-sip.sh`). They persist across Magisk module reinstalls and reboots, but a `/data/app` uninstall wipes app data.

**Default SIP peer (SignalWire test rig):** fresh installs with empty prefs auto-register to `gateway@loomli-gsm-gateway.dapp.signalwire.com:5060` (UDP, IP auth — no password). Baked in via `BuildConfig` / `SipConfig`; `./deploy.sh` also runs `scripts/configure-sip.sh --force` to write prefs on-device. Override in Settings, via env vars to `configure-sip.sh`, or by changing `DEFAULT_SIP_*` in `app/build.gradle.kts`.

## VOICE_CALL capture (the core capture mechanism)

`AudioRecord(VOICE_CALL)` is how the gateway captures the mixed uplink+downlink modem audio. On Android 16, it reads all-zero PCM unless **both** of these hold (proven on-device; full writeup in `docs/VOICE_CALL_SILENCING_INVESTIGATION.md`):

1. **Declare + allowlist `CALL_AUDIO_INTERCEPTION`.** The permission is `signature|privileged|role` and reachable via the privileged path. It must be in `AndroidManifest.xml` **and** in the Magisk privapp-permissions allowlist (`magisk/system/etc/permissions/privapp-permissions-gateway.xml`). Without it, capture relies on a legacy `CAPTURE_AUDIO_OUTPUT` fallback that AOSP intends to remove.

2. **Start the foreground service while the app is foreground.** `RECORD_AUDIO`'s UID appop mode defaults to `MODE_FOREGROUND`, and `AppOpsService.evalMode` returns `MODE_IGNORED` unless the app holds `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE`. That capability only sticks for the FGS lifetime if the FGS was started while the app was foreground — otherwise the screen-off demotion to `PROCESS_STATE_IMPORTANT_FOREGROUND` (procState 4) drops the M bit and re-silences. `MicCapabilityGuard` handles this headlessly: `BootReceiver` root-launches `MainActivity` with `EXTRA_MIC_CAPABILITY_RELAUNCH` (invisible trampoline via `am start`), which calls `GatewayService.relaunchFromForeground(...)` and then finishes. `GatewayService` also checks on start and runs a periodic monitor. Onboarding still relaunches from `MainActivity` for the first manual setup.

This is the difference between live audio and silence. Verify after a build:

```bash
adb reboot && adb wait-for-device && sleep 45   # cold boot — do NOT open app manually
adb shell dumpsys activity processes | grep -A25 "com.callagent" | grep curCapability
# expect --MNFUATI (M present) with screen off, no manual app open
adb shell dumpsys audio | grep "src:VOICE_CALL" | tail -2   # expect "not silenced" during a call
adb shell dumpsys package com.callagent.gateway | grep CALL_AUDIO_INTERCEPTION   # granted=true
adb logcat -d -s MicCapabilityGuard:* BootReceiver:* | tail -20   # root relaunch trail
```

`VOICE_DOWNLINK` is never policy-silenced (it bypasses the appop check), but on the Pixel 7 it routes to an empty capture device at the HAL level — the native-ALSA bypass exists for direct downlink capture independent of this path.

## Devices

Goal: support as many Android devices as possible. Each device is handled by a `DeviceProfile` (`app/src/main/java/com/callagent/gateway/DeviceProfile.kt`) that encodes its SoC/codec mixer controls. Devices with tuned, tested profiles:

- **Google Pixel 7** — Tensor G1, aoc-snd-card
- **Samsung Galaxy S10e** — Exynos 9820, ABOX/Madera
- **Generic MSM8953** — Snapdragon 625 (e.g. some Xiaomi)
- **Samsung Galaxy S4 Mini** — MSM8930, WCD9304

Unknown devices auto-detect to a generic Qualcomm / Exynos / Generic profile (Android APIs only, no mixer hacks). To support a new device: add a profile, dump its `tinymix` controls via the in-app diagnostics, and tune the mixer setup/restore commands.

**Pixel 7 requires the uplink-routing mixer fix.** `DeviceProfile.pixel7Tensor()` sets `tinymix 'Incall Capture Stream0' UL` in `mixerSetupCmd`. Without this the AOC DSP routes nothing into the incall capture ring buffer, and `AudioRecord(VOICE_CALL)` reads silence *even after the policy fix above is applied*. The original DL (downlink-only) setting captured the agent's response instead of the caller's voice, causing echo-gated silence on the SIP side. UL routes the caller's uplink into the capture buffer; with `playbackToTelephony=true`, the agent's audio goes via the EP6 INCALL_TX mixer path instead. Both fixes are required on the Pixel 7.

## Test rig (SignalWire + bridge-worker)

**Default SIP peer:** SignalWire Domain Application at `loomli-gsm-gateway.dapp.signalwire.com` (username `gateway`, IP auth). Provision with `scripts/signalwire/create-domain-app.sh`; push prefs with `scripts/configure-sip.sh`.

`bridge-worker/` contains a Cloudflare Worker used as **optional downstream** for end-to-end verification (Twilio Elastic SIP Trunk → OpenAI Realtime). It is **not** part of the gateway — it's downstream SIP infrastructure. The gateway itself is SIP-agnostic.

```bash
cd bridge-worker
npx wrangler deploy         # deploy
npx wrangler secret put OPENAI_API_KEY        # set secrets
npx wrangler secret put OPENAI_WEBHOOK_SECRET
npx wrangler tail           # live logs
```

Two non-obvious bugs fixed in this Worker that are worth not re-introducing:

- **Webhook HMAC key:** the `whsec_`-prefixed secret must be **stripped of `whsec_`** and **base64-decoded** before import as the HMAC key (`verifyWebhook` in `bridge-worker/src/index.js`). Using the raw string silently fails signature verification.
- **WebSocket auth in Workers:** the OpenAI monitoring socket must authenticate via **subprotocols**, not headers (Cloudflare Workers can't set arbitrary headers on a `WebSocket`). The API key is passed as `openai-insecure-api-key.<key>` in the subprotocol list.

## Restoring Root After Android Update

Android updates overwrite the patched init_boot, killing Magisk root. Re-patch and flash to the **active** slot:

```bash
adb shell getprop ro.boot.slot_suffix          # _a or _b — flash to THIS slot only
# matching panther factory image: https://developers.google.com/android/images
adb push init_boot.img /sdcard/Download/       # then Magisk app → Install → Select and Patch a File
adb pull /sdcard/Download/magisk_patched-*.img
adb reboot bootloader
fastboot flash init_boot_b magisk_patched.img   # match _b/_a to active slot
fastboot reboot
```

Gotchas:
- **Flash the active slot only** (`init_boot_a`/`init_boot_b` per `ro.boot.slot_suffix`), not both.
- **Can't `dd`-extract init_boot** on production builds (`adb root` is denied) — must use the factory image.
- **On WSL**, native `fastboot` is broken — use the Windows binary at `/mnt/c/platform-tools/fastboot.exe`. On native Linux, the system `fastboot` works directly.
- **Bootloader stays unlocked** across updates; no re-unlock needed.
- **Re-install the gateway Magisk module** after restoring root (audio-concurrency bypass + privapp perms).
- Drive the phone with `scrcpy` if phantom touches make the screen unusable.
