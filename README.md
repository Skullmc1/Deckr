# Deckr

Use your Android phone as a touchpad, keyboard and gamepad for your Windows PC, over Bluetooth.

- **Touchpad:** tap to click, two fingers to scroll or right-click, hold and drag to select.
- **Keyboard:** type on your phone and it appears on the PC as you type.
- **Gamepad:** two touch joysticks and PlayStation-style buttons, seen by Windows as a DualShock 4 or Xbox 360 controller.
- **Gyro aim:** turn and tilt the phone to aim in shooters, as mouse or right-stick input.

No pairing in Windows settings needed. The first time a phone connects, Deckr on the PC asks you to allow it.

## Screenshots

<p align="center">
  <img src="docs/screenshots/phone-gamepad.png" alt="Gamepad mode in landscape: shoulder buttons, D-pad, two joysticks, face buttons, and gyro aim switched on" width="800">
</p>

<p align="center">
  <img src="docs/screenshots/phone-connect.png" alt="Connect screen looking for the PC" width="240">
  &nbsp;
  <img src="docs/screenshots/phone-touchpad.png" alt="Touchpad mode with the tool strip at the bottom" width="240">
</p>

<p align="center">
  <img src="docs/screenshots/phone-gyro.png" alt="Gyro aim settings: aim with mouse or right stick, always or while holding L2, sensitivity" width="800">
</p>

<p align="center">
  <img src="docs/screenshots/desktop-ask.png" alt="Deckr for Windows asking whether to let a new phone connect" width="380">
  &nbsp;
  <img src="docs/screenshots/desktop-on.png" alt="Deckr for Windows with a phone connected" width="380">
</p>

## Install

Download both from the [latest release](../../releases/latest):

1. **PC:** run `Deckr-Setup.exe`. Deckr lives in the tray and starts with Windows.
2. **Phone:** install `Deckr.apk` (Android 8 or newer).
3. Open Deckr on the phone. It finds your PC and connects. Click **Allow** on the PC the first time.

Gamepad mode needs the free [ViGEmBus](https://github.com/nefarius/ViGEmBus/releases/latest) driver on the PC. Deckr points you to it if it's missing.

## Project

| Folder     | What it is |
|------------|------------|
| `android/` | Phone app (Kotlin, Jetpack Compose) |
| `desktop/` | Windows app (Tauri 2: Rust backend in `src-tauri/`, UI in `ui/`) |

**Build locally**

```bash
# Windows app (needs Rust, Node and the Visual Studio C++ tools)
cd desktop && npm ci && npx tauri build

# Android app
cd android && ./gradlew assembleRelease
```

## Releases

Pushing a tag like `v0.4.1` runs `.github/workflows/release.yml`, which builds both apps and
publishes them as a GitHub release. The APK is signed with the key stored in these repository secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
