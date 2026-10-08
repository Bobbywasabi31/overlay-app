# Privacy — Throw Assistant

Throw Assistant is an on-device screen overlay. This document states what it
captures, what it never does, and which guardrails are enforced in CI.

## What the app captures

- **Screen frames**, via Android's MediaProjection API, only while a capture
  session is running. Every session requires the user's explicit consent in
  the system screen-sharing dialog.
- Frames are analyzed **in memory, on this device, in real time** to locate
  the throw ring. The analyzer looks only at the center region of the frame
  and discards everything else.
- The floating controls, guidance marker, and debug HUD are overlays drawn
  by the app itself.

## What the app never does

- **No network.** The manifest does not request `android.permission.INTERNET`,
  and CI fails the build if it is ever added (see `.github/workflows/android.yml`,
  "Verify manifest privacy guards").
- **Frames are never saved, uploaded, or logged.** There is no analytics, no
  crash reporting, and no account.
- **No backups.** `android:allowBackup="false"` and no `fullBackupContent`
  rules file, so app data (ball calibration, acknowledgement flags) never
  leaves the device through backup. CI fails the build if this changes.

## Session telemetry (opt-in, off by default)

A toggle in the main screen enables a per-session numeric stats log for
ring-detection debugging (issue #13). While enabled, each analyzed frame
appends one CSV row: relative timestamp, analysis time, candidate count,
per-reason rejection counts, and the analyzer + tracker ring-radius series.
Numeric only — no pixels, frames, or screenshots are ever stored. Logs live
in the app-private files directory (never backed up, never leaves the
device; the app has no network access), are pruned to the 10 most recent
sessions, and are all deleted when the toggle is turned off.

## Session limits

- A session **auto-stops after 10 minutes without a detection**, so a
  forgotten full-screen-sharing session cannot linger.
- The user can stop capture at any time from the floating controls or the
  persistent notification.

## Accessibility service

`GestureThrowService` exists for one purpose: performing the hold-ball and
swipe gestures the user armed via Auto. It does not read other apps' UI
content, and it dispatches no gesture unless Auto is armed and a stable
ring was detected. A dry-run mode exercises the same pipeline with all
gesture dispatch disabled.

## Consent

On first run, and again before Auto can arm, the app shows a notice that
using assistance like this with Pokémon GO may violate the game's terms of
service and risks an account ban. Auto cannot arm until the notice is
acknowledged.
