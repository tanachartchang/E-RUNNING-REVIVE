# E-RUNNING-REVIVE

A free, open-source replacement app for **E-RUNNING Bluetooth treadmills**. The original
E-RUNNING app (2016) no longer works on modern Android phones, and the manufacturer has
stopped supporting it. This project brings those treadmills back to life.

- **No account, no server, no tracking.** Your workout data stays on your phone.
- Works with the treadmill's Bluetooth (BLE) connection, reverse engineered from the original app.
- Shows heart rate from any standard Bluetooth heart-rate broadcast (for example, a smartwatch with heart-rate broadcasting turned on).

## What's in this repo

| Folder | What it is |
|---|---|
| [`android-app/`](android-app/) | Native Android app (Kotlin + Jetpack Compose), Android 8.0+ |
| [`treadmill-app/`](treadmill-app/) | Web app (PWA) using Web Bluetooth. Runs in Chrome/Edge on Android or desktop, with no install needed |

## Android app

Build a debug APK (needs JDK 17+ and the Android SDK):

```bash
cd android-app
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Web app

Serve the folder over HTTPS (or `localhost`) and open it in a Chromium-based browser:

```bash
cd treadmill-app
python3 -m http.server 8000
# open http://localhost:8000
```

Web Bluetooth isn't available in Safari/iOS or Firefox. Use the Android app there.

## Disclaimer

This is an independent community project. It is not affiliated with or endorsed by the
treadmill's manufacturer. "E-RUNNING" is used only to describe compatibility. Use at your
own risk, and always keep the treadmill's safety key attached while running.
