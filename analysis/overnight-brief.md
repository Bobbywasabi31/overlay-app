# Throw Assistant — overnight brief on Claude's sprint

Repo: `Bobbywasabi31/overlay-app` @ v0.3.2-alpha.5 (commit c212d88).
Numbering below follows Claude's pasted list (note: your earlier relay had 53/61 swapped — the real list is **53=Dependabot, 61=LICENSE**).

## Verdicts up front

- **All 10 code claims I could check are confirmed.** The 2 process items (1, 51) are correctly scoped as needing Alex.
- **The sprint is well-picked.** All 12 are tagged `(safe)` — none deepen automation. Nothing I'd cut.
- **21 and 85 are one task** (debug HUD). 44 should land before new code, not after.
- **Two gaps in the plan:** the CI workflow has **no release/publish job at all** (releases are manual — v0.3.2-alpha.5's APK+checksum didn't come from CI), so item 51's signing has nowhere to plug in; and there's no `docs/device-matrix.md` template for item 1 (I can pre-write it).
- **Needs Alex:** item 1 (physical phones), item 51 (keystore + secrets), item 61 (copyright holder name).

## Per-item verification

### 1. [P1·M] Device testing — needs Alex
Process item, nothing to verify in code. README's "accuracy and game compatibility remain unvalidated" confirms the gap is real. I can pre-write `docs/device-matrix.md` with the checklist (Android 8/12/14/15 × continued-touch, overlay alignment, touch pass-through, rotation cleanup); Alex fills in results.

### 3. [P1·S] Marker flicker — CONFIRMED
`RingTracker.update()` (`RingTracker.kt:9`) returns null on any single miss (`candidate == null || old == null`), and `OverlayService.startCapture` passes that straight to `guidance?.showRing(ring)` — one dropped frame blanks the marker. **Approach:** add `displayTarget(nowMs)` to RingTracker with a 150 ms hold of the last *confirmed* ring; service calls it for `showRing`, keeps strict `update()` result for `maybeThrow`. **Gotchas:** existing `RingTrackerTest` (6 tests) pins `update()` semantics — don't touch it, only add; pass time explicitly (service uses `elapsedRealtime`, tracker defaults to `nanoTime`/1e6 — both monotonic ms, but be explicit); the 900 ms stale branch in the service already nulls the marker, so the 150 ms hold nests inside it cleanly.

### 17. [P1·M] Center-region analysis — CONFIRMED
`ScreenAnalyzer.analyze()` builds the color mask over the full frame, flood-fills everything, and only then rejects centers outside x 12–88% / y 18–82%. **Approach:** derive region bounds from those same constants; restrict the mask loop, seed loop, and flood-fill neighbor clamp to the region; replace the frame-edge discard with a region-edge discard. **Gotcha (real):** rings centered inside the region but with pixels extending past it (radius up to 0.38×shortSide is accepted today) get clipped and discarded — a behavior change. Real-world impact should be nil (the game ring is central), but items 11/12 (fixture set) don't exist to prove it. Existing tests (`rejectsRingClippedAtEdge`, `detectsRedYellowAndGreenRings`, `ignoresSmallInterfaceIcons`) stay green by inspection. Recommend adding one regression test: large centered ring.

### 21. [P1·S] Per-frame timing → merge into 85
No timing instrumentation exists in the analysis path. Implement once as part of the debug HUD below.

### 30. [P1·S] Idle auto-stop — CONFIRMED missing
No idle handling exists (`IDLE` in the code is just a session phase). **Approach:** `lastActivityMs` in OverlayService, refreshed on confirmed ring, `toggleAuto`, `startCalibration`, and throw dispatch; the existing 300 ms watchdog checks `now - lastActivity > IDLE_STOP_MS` → `endSession()` with a new `message_idle_stopped` string. **Gotcha:** "configurable" has nowhere to live — no settings UI exists. Ship a constant (10 min is the sane default for a full-screen-sharing privacy risk) and note settings UI as follow-up.

### 44. [P1·S] JaCoCo — CONFIRMED missing
**Approach:** apply the `jacoco` plugin, add a `jacocoTestReport` task on `testDebugUnitTest` exec data, plus a verification task that parses the XML and fails below a floor. **Gotchas:** on AGP 8.7, `enableUnitTestCoverage` is deprecated — wire the report task explicitly instead; `unitTests.isIncludeAndroidResources = true` is already set (good); **set the first floor conservatively** (measure current, floor at ~current−5%) or the sprint's own PRs will red-build on day one; then ratchet.

### 51. [P1·M] Release signing — needs Alex, and one plan gap
Confirmed: no `signingConfigs`, and only a `debug` buildType exists. **Approach:** add a `release` buildType + signing config that applies **only when the keystore is present** (env/file check), falling back to debug signing otherwise so CI stays green with no secrets. **Gotchas:** Alex must generate the keystore and add 4 secrets (keystore b64, alias, key password, store password) — I can't do that part. **Bigger gap:** `.github/workflows/android.yml` has no publish step at all (build → upload artifacts, that's it). The v0.3.2-alpha.5 release with APK+SHA256SUMS was made manually. Signing in CI is pointless without a release job — add one (or document the manual `gh release create` flow) as part of this item.

### 53. [P1·S] Dependabot — CONFIRMED missing
`.github/` contains only `workflows/`. Trivial: `dependabot.yml` with `gradle` + `github-actions`, weekly. No conflict with the lint config's disabled `GradleDependency` advisories (those suppress lint noise; Dependabot PRs are still useful).

### 61. [P1·S] LICENSE — CONFIRMED missing
No LICENSE file, no license mention in README. MIT matches the Cash Compass precedent. **Needs Alex: copyright holder name** (repo gives no attribution; "Bobbywasabi31" is the placeholder until he says otherwise).

### 85. [P1·M] Debug HUD (implements 21 too) — CONFIRMED missing
**Approach:** `ScreenAnalyzer` exposes `data class Stats(analysisMs, candidates, rejected: Map<String,Int>)` populated per `analyze()` call (count rejections at each existing `continue`: edge/aspect/center/radius/fill/shape); new small overlay view in `OverlayService`, updated throttled (~4 Hz); toggle button in floating controls, default off, session-only; budget constant (40 ms) shown red when exceeded. **Gotcha:** the HUD is itself screen-captured — keep it small, cornered, outside the center region; its colors can't pollute detection anyway (white text fails the saturation gate, and cyan is excluded by design).

### 90. [P1·M] Dry-run mode — CONFIRMED missing
No dry-run flag anywhere. **Approach:** `dryRun` boolean on `GestureThrowService` (default off, toggle in floating controls, session-only). In `holdBall`: if dry-run, log the intent, synthesize the `HeldBall` record with `ready=true` immediately, **never call `dispatchGesture`**. In `throwBall`: log the planned swipe, fake `completed(true)`, no dispatch. `releaseHold`/`cancelHold`: clear state without dispatch. `OverlayService.maybeThrow` stays untouched so the real `AutoThrowController` state machine (consider → beginThrow → finishThrow) is genuinely exercised. **Gotchas:** `busy` must be set/cleared symmetrically or Auto stalls mid-pipeline; the 1500 ms `holdTimeout` still fires → must hit the dry-run clear path, not a real release; never create a real `held` pointer while skipping its release dispatch — the design above avoids this by construction. Claude tagged it `(safe)` — correct, it subtracts automation.

### 91. [P1·S] ToS / ban-risk notice — CONFIRMED missing
No strings, no dialog; the warning lives only in README prose. **Approach:** `SharedPreferences` flag `tos_acknowledged`; `MainActivity` shows an `AlertDialog` (new strings: title/body/acknowledge) on first Start and blocks starting until acknowledged; `OverlayService.toggleAuto()` re-checks the flag — if false, toast + launch MainActivity with an extra to show the dialog, don't arm. **Gotcha:** service→activity launch needs `FLAG_ACTIVITY_NEW_TASK`; keep the copy aligned with the README's existing ban-risk wording so the two can't drift.

## Sprint as a set

**Suggested order:** 61 + 53 (trivial, 10 min) → 44 (before new code lands, floor set conservatively) → 3 and 17 (independent, pure logic + unit tests) → 21+85 (single HUD task, consumes 17's analyzer stats) → 90, 30, 91 (independent features, any order) → 51 wiring (whenever; activates when Alex adds secrets).

**Cuts:** none. It's a tight, all-`(safe)` sprint.

**Add as stretch:** item 69 (CI check that the INTERNET permission stays absent) — P3·S, ~10 minutes, and it's the automated guard for the app's core privacy claim. The manifest today is clean (only SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION, POST_NOTIFICATIONS — claim verified).

**Out-of-sprint note:** the metrics-driven OverlayService split is item 31 [P1·L] — deliberately not in this sprint. I started reading toward it and stopped; flagging so it doesn't look dropped.

## Minor notes

- Claude's metrics say 77 tests; I count 79 `@Test` annotations. Close enough — not a red flag.
- None of the 5 `(auto)` items (32, 73, 83, 92 — 98 was Claude's own correction, it's untagged) are in this sprint. The sprint is automation-neutral-to-negative.
- `warningsAsErrors = true` in lint: all new code must be lint-clean, and new strings must actually be referenced.
