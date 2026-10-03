# Device test matrix — Throw Assistant

Record results for each device. The app targets API 24+ (Android 7.0);
priority versions are **8, 12, 14, 15**.

| Device | Android | Continued touch works | Overlay alignment | Touch pass-through | Rotation cleanup | Notes |
|---|---|---|---|---|---|---|
| _example: Pixel 6_ | _14_ | _pass_ | _pass_ | _pass_ | _pass_ | __ |
| | 8 | | | | | |
| | 12 | | | | | |
| | 14 | | | | | |
| | 15 | | | | | |

## What to check per device

- **Continued touch works**: arm Auto with a stable ring; confirm the
  hold-then-swipe gesture fires through the accessibility service.
- **Overlay alignment**: the ring marker sits on the actual target ring,
  in portrait, on the device's real screen size.
- **Touch pass-through**: overlay regions outside the marker/controls
  don't swallow game taps.
- **Rotation cleanup**: rotate mid-session; confirm no orphaned views
  and the session recovers or stops cleanly.

## Regression: dry-run mode

Before risking gestures on a real account, enable **Dry run** in the
floating controls and confirm logcat shows `dry_run_would_hold` /
`dry_run_would_throw` entries with no gesture dispatched.
