# GSM-SIP Gateway

Bridges GSM calls (local SIM) with Asterisk via SIP. Upstream: https://github.com/rmeehub/gsm-sip-gateway

## Build & Install

Prereq: run `sudo ./setup.sh` once to install a JDK 17+ and the Android SDK (auto-detects `openjdk-21-jdk` on Debian 13/trixie, else `openjdk-17-jdk`).

```bash
./build.sh          # debug
./build.sh release  # release → outputs gateway-magisk.zip
```

Install the Magisk module (`gateway-magisk.zip`) via Magisk Manager, reboot, set as default phone app.

## Redeploying after a code change

The app is installed as a Magisk **system priv-app**, so `adb install gateway.apk` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch). Reinstall the module instead, then reboot so the overlay swaps the system APK:

```bash
adb push gateway-magisk.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/gateway-magisk.zip"
adb reboot
```

`versionCode` is not bumped across commits, so verify the new code is live by md5, not by the on-device version:

```bash
md5sum gateway.apk                                             # host
adb shell su -c "md5sum /system/priv-app/Gateway/Gateway.apk"   # must match
```

## Devices

- **Pixel 7** (panther) — used for Magisk/root work
- **Samsung Galaxy S10e** — target gateway device with LineageOS

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
