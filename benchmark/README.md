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

## Telemetry summarizer

- `summarize_telemetry.py` — reads the session-telemetry CSVs written by
  `SessionTelemetry` (task 8, opt-in on the main screen) and prints a one-page
  summary per file plus a combined summary: frame count/span/fps,
  analysisMs mean/p95/max with spike count, candidate mean/max, detection and
  tracked rates, per-reason rejection totals with the dominant reason, the
  detected-radius series (min/max/mean + least-squares shrink rate per second,
  mirroring `AutoThrowController`'s prediction input from task 4), and anomaly
  notes (silent sessions, rejection dominance >80%, analysisMs spikes).
  `--json` emits the combined summary as sorted-key JSON. Columns are matched
  by name and unparseable rows are skipped and counted, so slightly drifted
  formats still parse. Exit 0 when at least one valid row was summarized,
  exit 2 otherwise.
- `test_summarize_telemetry.py` — 32 self-contained checks (no pytest needed):
  `python3 test_summarize_telemetry.py`.

```sh
python3 summarize_telemetry.py session-*.csv
python3 summarize_telemetry.py --json session-*.csv
```

## Comparing two runs (before/after)

`--vs` diffs the positional (target) session logs against a baseline set —
the before/after story when a detection gate is retuned and the same
encounter scenario is re-run:

```sh
python3 summarize_telemetry.py new-session.csv --vs old-session.csv
```

Prints numeric deltas per metric (target − baseline), per-reason rejection
deltas sorted by |delta|, and a CHANGED flag when the dominant rejection
reason moves. `--json --vs` embeds the same diff as the `"diff"` object.
Exit 2 when the baseline parses to zero rows.**21/22 pass** — 16/17 positives detected, 5/5 negatives correctly rejected.
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
