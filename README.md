# Insta360 X5 Remote — Karoo 3

A minimal Android app to scan for and connect to an Insta360 X5 over BLE
from a Hammerhead Karoo 3, with buttons for record / photo / highlight.

## ⚠️ Status: connection works, camera-control protocol is unverified

- **Scanning + connecting to the camera by name works out of the box** —
  this is standard Android BLE and doesn't depend on any Insta360-specific
  details.
- **The REC / PHOTO / HIGHLIGHT buttons use placeholder GATT UUIDs and
  command bytes** carried over from an earlier community project. Insta360
  does not publish a public BLE spec, so these are *not* confirmed to
  actually control the camera yet.

When you connect successfully, the app logs every GATT service and
characteristic UUID the camera exposes (via `adb logcat`, filter on tag
`Insta360BluetoothService`). That's the starting point for working out the
real protocol.

### Recommended next step: capture the real protocol

1. On an Android phone, enable Developer Options → **Enable Bluetooth HCI
   snoop log**
2. Open the **official Insta360 app**, connect to the X5, and tap Record /
   Photo once each
3. Disable the snoop log, pull the log file (`Settings → System → Developer
   options → Bluetooth HCI snoop log` saves to
   `/sdcard/btsnoop_hci.log` on most devices, or use `adb bugreport`)
4. Open it in **Wireshark** (`Analyze → Follow → BTLE` or filter on
   `btatt`) and find the actual `Write Request` / `Write Command` packets
   sent when you tap Record — that gives you the real characteristic UUID
   and command bytes
5. Update `CMD_START_RECORDING` etc. and the UUIDs in
   `Insta360BluetoothService.kt` to match

## Building

CI builds a debug APK automatically on every push to `main` — check the
**Actions** tab, then the **Artifacts** section of a successful run.

To build locally:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17.
