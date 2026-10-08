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
  2026-10-08 investigation found). Diagnosis: on this 295px-wide JPEG the ring
  is anti-aliased and faint — only 9 of 1372 annulus pixels pass the mask
  (1343 killed by the local-contrast high-pass, 20 by the hue gate, 0 by
  brightness). The surviving fragments die as tiny/aspect/shape components.
  This is the fragmentation problem task 20 (fragment-tolerant arc-fallback
  pass) is meant to move. The hue gate is innocent.

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
