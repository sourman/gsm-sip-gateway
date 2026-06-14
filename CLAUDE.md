# GSM-SIP Gateway

Bridges GSM calls (local SIM) with Asterisk via SIP. Upstream: https://github.com/rmeehub/gsm-sip-gateway

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

Android updates overwrite the patched init_boot, killing Magisk root. Re-patch and flash to the **active** slot:

```bash
adb shell getprop ro.boot.slot_suffix          # _a or _b — flash to THIS slot only
# matching panther factory image: https://developers.google.cn/android/images
adb push init_boot.img /sdcard/Download/       # then Magisk app → Install → Select and Patch a File
adb pull /sdcard/Download/magisk_patched-*.img
adb reboot bootloader
/mnt/c/platform-tools/fastboot.exe flash init_boot_b magisk_patched.img   # match _b/_a to slot
/mnt/c/platform-tools/fastboot.exe reboot
```

Gotchas:
- **Flash the active slot only** (`init_boot_a`/`init_boot_b` per `ro.boot.slot_suffix`), not both.
- **Can't `dd`-extract init_boot** on production builds (`adb root` is denied) — must use the factory image.
- **WSL fastboot is broken** — use the Windows binary at `/mnt/c/platform-tools/fastboot.exe`.
- **Bootloader stays unlocked** across updates; no re-unlock needed.
- **Re-install the gateway Magisk module** after restoring root (audio-concurrency bypass + privapp perms).
- Drive the phone with `scrcpy` if phantom touches make the screen unusable.
