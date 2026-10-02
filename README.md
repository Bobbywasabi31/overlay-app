# Throw Assistant

An experimental Android overlay that detects colored rings and draws a cyan target marker. It does not identify Pokémon, calculate ballistic trajectories, or classify “Excellent” throws. An optional automatic thrower can send a configurable straight swipe in Pokémon GO after explicit arming and ball-position calibration.

## Features

- A complete Android Studio / Gradle project with one consistent application package.
- User-initiated, full-display capture through Android's MediaProjection consent flow.
- A foreground service with a notification and Stop action.
- Non-touchable guidance with opacity limited for Android 12's touch security rules, plus small touchable floating controls.
- Optional gesture service, ball-position calibration, adjustable swipe duration, and automatic throwing with a five-attempt limit per arming.
- On-device red, orange, yellow, and green ring detection, with geometry checks and two-frame confirmation.
- A dark control screen with permission and session status, and a full-screen practice encounter.
- Resource cleanup on Stop, projection revocation, screen off, or display changes. Sessions never restart automatically or reuse consent tokens.

Screen frames stay in memory and are never stored or uploaded. The app requests no Internet, storage, microphone, or camera permission. Automatic throws require separately enabling the optional Android Accessibility service. That service checks the focused application package and window bounds; it does not read or store UI text. Overlay guidance works without it. Stop capture before opening private content because sharing covers the full display.

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
4. Open **Practice screen**. The full-window encounter uses a shrinking ring and a draggable ball. Swipe from the ball toward the ring in 150–600 ms to receive inner-ring, outer-target, or miss feedback. Use the ring-color, small-pale-ring, and movement controls for detector checks. Touch and throw counters report received input.
5. Switch to your target app. Stop from the controls, notification, or Android's screen-sharing controls. If notifications are disabled, use the app or Android's sharing / active-app controls.

Changing display geometry, including rotation, ends the session to prevent misaligned guidance. Start a new session afterward. Screen locking or another capture app can also end sharing. Every new session requires fresh consent.

## Optional automatic throws

1. In the control screen, open gesture permission settings and enable **Throw Assistant gestures** in Android Accessibility settings. Android requires this separate permission to send swipes.
2. Start screen capture, then open a Pokémon GO encounter or the practice encounter in portrait with the ball visible. Gesture control is restricted to the focused Pokémon GO window or this app while its practice activity is resumed. The main controls and other apps are rejected. Unknown foregrounds and split-screen-sized windows are rejected.
3. Tap **Set ball** in the floating controls, then tap the center of the ball in the lower screen. This calibration tap is intercepted and is not sent to the underlying encounter. Calibration is scoped to either practice or the game; switching targets requires calibrating again. The calibration overlay disappears after selection or times out after 15 seconds.
4. Tap **Auto: off** in the floating controls to arm. A fresh, consistent ring must remain near the same position for at least 350 ms before a straight swipe is submitted from the calibrated ball position to the ring center. The swipe duration is adjustable from 150–600 ms in the app; the default is 350 ms.
5. A persistent ring cannot repeatedly trigger swipes. Further attempts require at least 1.2 seconds of observed ring absence, fresh confirmation, and a 3-second cooldown. Automatic throwing disarms after five dispatched attempts, on dispatch failure/cancellation, when the foreground changes, or when capture stops. Tap Auto again to start another batch.
6. **Stop** in the floating controls, app, notification, or Android sharing controls ends capture. Rotation, locking, permission loss, and capture restart clear arming and calibration. No consent token, calibration, or armed state is persisted. A swipe already submitted to Android may finish; stopping prevents future swipes.

The swipe is an experimental input helper, not a calibrated model of ball physics. It does not verify ball availability, recognize encounter state, predict a catch or throw grade, or guarantee that a swipe will hit. The ring detector can mistake another circular object for a target. Device testing must tune the ball origin and duration; accuracy and game compatibility remain unvalidated. The practice scene grades the swipe endpoint against the shrinking ring at release time. It does not model game ballistics or validate gameplay accuracy. Ring and ball disappear for 2.4 seconds after an accepted throw, exercising the same observed-absence/cooldown checks as the live pipeline. Practice uses the real capture/analyzer/tracker/gesture path; there is no special shortcut that reports a hit to the detector. Normal touch pass-through applies outside the floating buttons; the full-screen calibration overlay temporarily intercepts touches intentionally.

## Detection and limits

The pure Kotlin analyzer uses a color mask, connected components, thin-ring checks, circular geometry, and angular coverage. It searches the central display region, excludes cyan to avoid detecting its own guidance, and requires two nearby detections before displaying a target. Frames are downscaled to at most 960 pixels on the long side and analyzed at most about fifteen times per second (67 ms minimum interval) on a worker thread. Skipped frames are closed too. Misses clear the marker immediately; stalled capture clears it after about a second and requires fresh two-frame confirmation on resumption. Results older than 900 ms are discarded before drawing. Automatic throws require a result no older than 200 ms.

This is a heuristic, not a trained model or calibrated gameplay assistant. Busy backgrounds, occlusion, tiny rings, unusual colors, or overlapping objects can cause missed or false detections. Other apps may block overlays or protect screen content. Passing synthetic tests does not establish gameplay accuracy or compatibility with every device.

The original OpenCV imports referenced a module that did not exist. The Kotlin implementation removes that broken dependency and avoids native ABI requirements.

## Validation

Unit tests cover ring colors, negative shapes, cyan self-detection, clipped rings, noise, portrait/landscape normalization, tracking expiry, delayed result rejection, swipe planning, automatic-throw stability/cooldown/disappearance/limits, termination ordering, and RGBA row/pixel padding. Android Lint fails on warnings, except remote dependency-update recommendations and the time-dependent `OldTargetApi` upgrade advisory. This prototype intentionally targets API 35; a newer target requires separate behavior testing before release.

Device smoke checks still required:

- Fresh launch: no permission dialogs until Start or Settings is tapped.
- Deny overlay permission, cancel capture, and deny notification permission. Controls should recover without crashes or silent capture.
- Approve capture and open Practice screen. Verify alignment, movement, and touch-counter updates.
- Stop from the app, notification, and Android sharing control. Check overlay removal and stopped status.
- Repeat Start/Stop, rapidly tap Start, and rotate during permission flow and capture.
- Lock the screen, revoke overlay permission, and start another capture app. Check cleanup and fresh consent on restart.
- Enable/disable the gesture service; ensure throws require calibration and explicit arming. Run Auto in Practice, verify injected touches produce visible throw feedback, then switch to the control screen and another app and verify immediate disarming. Recalibrate when switching from practice to Pokémon GO. Verify no swipes in other apps, no repeats for a persistent ring, correct batch limit, cancellation recovery, swipe duration, and clearing calibration after rotation or restart. Check that the calibration overlay is removed on timeout and Stop.
- Check large fonts, gesture and three-button navigation, and touch pass-through to another app on physical Android 12+ hardware. The practice screen alone cannot test Android's cross-app touch security rules.

## Layout

`app/src/main` contains the manifest, Kotlin source, and resources. `app/src/test` contains JVM tests. `.github/workflows/android.yml` builds and validates the app.

This educational project is unaffiliated with Niantic or The Pokémon Company. The original project's warning remains relevant: using assistance with Pokémon GO may violate the game's terms and risk an account ban.

## Alpha 2 detection fix

A reported small green target disappeared after downscaling because its blended pixels fell below the original color threshold. Capture now preserves more detail (960-pixel long edge), the color mask accepts antialiased rings, and the minimum ring size is smaller. Circularity, hollow-shape, angular-coverage, cyan rejection, and two-frame checks remain enabled. The reported screenshot detects offline; additional devices, ring sizes, and scenes still need testing.

## Alpha 3 capture and thrower changes

The alpha 2 color and shape thresholds and 960-pixel capture cap are retained. Capture sampling increased from 200 ms to 67 ms. Tracker evidence expires after a stall, delayed results cannot repaint old targets, and a terminating service keeps Start disabled until teardown. Resource releases are independent so one failure does not skip the remaining cleanup. The optional automatic thrower and small pale practice-ring mode are new in version `0.3.0-alpha.3` (code 4).

## Alpha 4 practice encounter

Practice now spans the entire window with insets applied only to its HUD. The ball begins at 50% display width and 85% display height; the ring centers at 44% display height and shrinks over a six-second cycle. Pale-ring and moving-target modes remain available. Manual and Accessibility swipes share the same touch-event handling and release-position scoring. Leaving Practice stops its animation and disarms Auto. Unit coverage checks geometry across screen sizes, release timing, missed/invalid/cancelled swipes, the detector-to-planner path at 960 pixels, target allowlisting, and scoped calibration. Version: `0.3.1-alpha.4` (code 5).
