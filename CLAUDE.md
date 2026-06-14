# GSM-SIP Gateway

Bridges GSM calls (local SIM) with Asterisk via SIP. Upstream: https://github.com/sourman/gsm-sip-gateway

## Build & Install

```bash
./build.sh          # debug
./build.sh release  # release → outputs gateway-magisk.zip
```

Install the Magisk module (`gateway-magisk.zip`) via Magisk Manager, reboot, set as default phone app.

## Devices

- **Pixel 7** (panther) — used for Magisk/root work
- **Samsung Galaxy S10e** — target gateway device with LineageOS

## Restoring Root After Android Update

Android updates overwrite the patched init_boot, killing Magisk root. Restore process:

1. **Check state**: `adb shell getprop ro.boot.slot_suffix` → note active slot (`_a` or `_b`)
2. **Get matching factory image**: `https://developers.google.cn/android/images?hl=en` → find panther build
3. **Extract init_boot.img** from factory zip → nested `image-panther-*.zip` → `init_boot.img`
4. **Push to phone**: `adb push init_boot.img /sdcard/Download/`
5. **Patch with Magisk app**: Install → Select and Patch a File → pick init_boot.img
6. **Pull patched image**: `adb pull /sdcard/Download/magisk_patched-*.img`
7. **Reboot to bootloader**: `adb reboot bootloader`
8. **Flash to active slot**: `fastboot flash init_boot_b magisk_patched.img` (match `_b`/`_a` to slot)
9. **Reboot**: `fastboot reboot`

### Notes
- Bootloader stays unlocked across updates — no need to re-unlock
- `adb root` doesn't work on production builds, can't dd extract — must use factory image
- Factory image URL pattern: `https://dl.google.com/dl/android/aosp/panther-<BUILD>-factory-<hash>.zip`
- Windows fastboot required from WSL: `/mnt/c/platform-tools/fastboot.exe`
- scrcpy useful when phantom touches make screen unusable
- The gateway app's Magisk module (audio concurrency bypass + privapp permissions) must be re-installed after restoring root

## Project Structure

- `app/` — Android app source (Kotlin)
- `magisk/` — Magisk module template (system.prop, privapp permissions, install script)
- `tools/` — Audio tools (tinymix, tinycap)
- `build.sh` — Builds APK and packages into Magisk module zip
