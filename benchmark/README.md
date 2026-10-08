# Ring-detection benchmark corpus (task 19)

"Measure, don't guess." This directory holds a labeled corpus + a faithful
Python port of `ScreenAnalyzer.analyze()` so future detection-gate tuning is
scored against numbers, not vibes.

## Layout

- `analyzer_port.py` — gate-for-gate Python port of
  `app/src/main/java/com/bobbywasabi/overlayapp/ScreenAnalyzer.kt`.
  **Any change to the Kotlin gates/thresholds must be mirrored here before
  trusting benchmark numbers again.**
- `generate_corpus.py` — deterministic synthetic case generator (seeded;
  no binary images committed for synthetic cases).
- `corpus.json` — the labeled set: 15 synthetic positives (red/yellow/orange/
  green × clean/grass/busy backgrounds × sizes), 5 synthetic negatives
  (solid disk, cyan ring, blank, grass/busy with no ring), and 2 real public
  encounter screenshots with approximate hand labels (see `source` provenance).
- `corpus/real/` — the two real screenshots (public, fetched 2026-10-08;
  source URLs in `corpus.json`).
- `run_benchmark.py` — harness: runs every case, scores detection vs
  expectation, prints per-case center/radius error + rejection breakdown,
  aggregates misses by rejection reason. Exit 0 only when every case passes.

## Run

```sh
python3 run_benchmark.py
```

## Baseline (2026-10-08, ScreenAnalyzer v0.4.13-era logic)

**21/22 pass** — 16/17 positives detected, 5/5 negatives correctly rejected.
Full per-case table from `python3 run_benchmark.py`; the one miss:

- `real-red-ring-grass` — the genuine real-world hard case (a different public
  red-ring encounter shot than the 2026-10-03 one; same class as the miss the
  2026-10-08 investigation found). Diagnosis: the ring survives the mask as
  arc fragments that die as tiny/aspect/shape components in the
  connected-component pass. This is the fragmentation problem task 20
  (fragment-tolerant arc-fallback pass) is meant to move. The hue gate is
  innocent. NOTE: the original hand label for this case pointed at the sky
  (cy 0.358); re-measured 2026-10-08 against the visible ring (0.478, 0.483).

## Task 20 update (2026-10-08): arc-fallback pass

**22/22 pass** — 17/17 positives detected, 5/5 negatives correctly rejected.
When the connected-component pass finds nothing, `ScreenAnalyzer` (and the
port) now runs a fragment-tolerant second pass: Kasa algebraic circle fits
seeded from each large mask fragment (plus fragment pairs — Kasa is biased
toward small circles on short arcs), refit against all mask points near the
circle, accepted on inlier count (≥32), angular coverage (≥15/24 sectors —
a half-ring scores 14 and stays rejected), radial tightness (≤0.08), annulus
fill (≥0.30 — kills pair-seeded hallucinations on dense noise, measured
0.11–0.15 there vs 0.5–0.9 on true rings), and the thin-ring interior check.
Main-pass gates (radius, center region) are reused unchanged. Confidence is
capped at 0.85 so the main pass keeps precedence.

Known notes:

- Real cases are scored with loose tolerances (labels are approximate);
  they guard against regressions on real-world texture, not exact geometry.
- Synthetic `neg-*` cases must stay rejected — a "fix" that turns a negative
  into a detection is a regression, not an improvement.
- The harness exits non-zero while any case misses: the corpus is a tuning
  gate, and right now it honestly fails on the real-world case. Task 20's
  job is to flip `real-red-ring-grass` without breaking anything else.
- The analyzer has no JVM here; this port is the local gate. CI's
  `ScreenAnalyzerTest` remains the authoritative Kotlin check.

## Extending the corpus

Add cases to `corpus.json` (synthetic params or a real screenshot + approx
label). When Alex sends failing device screenshots, drop them in
`corpus/real/` and label them — those are the highest-value cases this
corpus can hold.
