# Throw Assistant

An experimental Android overlay that detects colored rings and draws a cyan target marker. It is a visual development prototype: it does not identify Pokémon, calculate trajectories, classify “Excellent” throws, or perform gestures.

## Features

- A complete Android Studio / Gradle project with one consistent application package.
- User-initiated, full-display capture through Android's MediaProjection consent flow.
- A foreground service with a notification and Stop action.
- A non-touchable overlay, with opacity limited for Android 12's touch security rules.
- On-device red, orange, yellow, and green ring detection, with geometry checks and two-frame confirmation.
- A dark control screen with permission and session status, and an animated practice screen.
- Resource cleanup on Stop, projection revocation, screen off, or display changes. Sessions never restart automatically or reuse consent tokens.

Screen frames stay in memory and are never stored or uploaded. The app requests no Internet, storage, microphone, camera, or accessibility permission. Stop capture before opening private content because sharing covers the full display.

## Build and install

Requires JDK 17 and Android SDK Platform 35. Open **the repository root** in Android Studio, not the `app` directory. The pinned toolchain is Gradle 8.10.2, Android Gradle Plugin 8.7.3, and Kotlin 2.0.21. No external OpenCV module or native library is needed.

```bash
# macOS / Linux
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

# Windows PowerShell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Set `ANDROID_HOME` to your SDK, or let Android Studio create `local.properties` with `sdk.dir`. The first build needs network access for dependencies. The app supports Android 7.0 / API 24 and newer and compiles/targets API 35.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install through Android Studio or:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs tests, lint, and assembly, and uploads a `throw-assistant-debug` artifact containing the APK. This is a debug build, not a signed production release. Builds from different machines can have different debug signing keys; Android will refuse an update signed with another key.

## Use

1. Open **Throw Assistant** and tap **Start overlay**.
2. Allow **Display over other apps** in Android Settings, then return. On Android 13+, notification permission is requested so the notification's Stop control is visible. That permission is separate from screen-sharing consent.
3. Approve Android's screen-sharing prompt. Share the **entire screen** if your device offers a choice; app-only sharing cannot reliably align a global overlay.
4. Open **Practice screen**. The cyan marker should follow the moving ring after two consistent detections. Tap to cycle green, yellow, and red. The touch counter checks that touches reach the practice view.
5. Switch to your target app. Stop from the controls, notification, or Android's screen-sharing controls. If notifications are disabled, use the app or Android's sharing / active-app controls.

Changing display geometry, including rotation, ends the session to prevent misaligned guidance. Start a new session afterward. Screen locking or another capture app can also end sharing. Every new session requires fresh consent.

## Detection and limits

The pure Kotlin analyzer uses a color mask, connected components, thin-ring checks, circular geometry, and angular coverage. It searches the central display region, excludes cyan to avoid detecting its own guidance, and requires two nearby detections before displaying a target. Frames are downscaled to at most 640 pixels on the long side and analyzed at most five times per second on a worker thread. Skipped frames are closed too. Misses clear the marker immediately; stalled capture clears it after about a second.

This is a heuristic, not a trained model or calibrated gameplay assistant. Busy backgrounds, occlusion, tiny rings, unusual colors, or overlapping objects can cause missed or false detections. Other apps may block overlays or protect screen content. Passing synthetic tests does not establish gameplay accuracy or compatibility with every device.

The original OpenCV imports referenced a module that did not exist. The Kotlin implementation removes that broken dependency and avoids native ABI requirements.

## Validation

Unit tests cover ring colors, negative shapes, cyan self-detection, clipped rings, noise, portrait/landscape normalization, tracking, and RGBA row/pixel padding. Android Lint fails on warnings, except remote dependency-update recommendations.

Device smoke checks still required:

- Fresh launch: no permission dialogs until Start or Settings is tapped.
- Deny overlay permission, cancel capture, and deny notification permission. Controls should recover without crashes or silent capture.
- Approve capture and open Practice screen. Verify alignment, movement, and touch-counter updates.
- Stop from the app, notification, and Android sharing control. Check overlay removal and stopped status.
- Repeat Start/Stop, rapidly tap Start, and rotate during permission flow and capture.
- Lock the screen, revoke overlay permission, and start another capture app. Check cleanup and fresh consent on restart.
- Check large fonts, gesture and three-button navigation, and touch pass-through to another app on physical Android 12+ hardware. The practice screen alone cannot test Android's cross-app touch security rules.

## Layout

`app/src/main` contains the manifest, Kotlin source, and resources. `app/src/test` contains JVM tests. `.github/workflows/android.yml` builds and validates the app.

This educational project is unaffiliated with Niantic or The Pokémon Company. The original project's warning remains relevant: using assistance with Pokémon GO may violate the game's terms and risk an account ban.
