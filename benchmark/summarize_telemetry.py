#!/usr/bin/env python3
"""Summarize Throw Assistant session-telemetry CSVs (task 8 output).

Task 8 logs one numeric-only CSV row per analyzed frame while a capture
session runs (opt-in). When Alex runs a field test and the logs come back,
this script turns them into a one-page summary instead of someone eyeballing
36k raw rows:

    python3 summarize_telemetry.py session-*.csv

What it reports, per file and combined:
- frames, wall-clock span, effective analysis fps
- analyzer analysisMs: mean / p95 / max (spots slow-frame regressions)
- candidate counts: mean / max
- detection rate (frames where the analyzer reported a radius),
  tracked rate (frames where RingTracker had a confirmed radius)
- per-reason rejection totals + the dominant rejection reason
  (this is the money column for #13 field debugging: if the dominant reason
  on a real encounter is e.g. "fill" or "shape", the gate at fault is named)
- detected-radius series: min/max/mean + least-squares shrink rate per
  second (mirrors AutoThrowController's prediction input from task 4)
- anomaly notes: silent sessions (no detections over a long run),
  analysisMs spikes, one rejection reason dominating >80% of rejections

Exit 0 when at least one valid row was summarized; exit 2 when nothing
could be parsed or the CLI usage is wrong. `--json` prints the combined
summary as sorted-key JSON for tooling.

The column layout mirrors SessionTelemetry.header()/logFrame() in
app/src/main/java/com/bobbywasabi/overlayapp/SessionTelemetry.kt:
tMs,analysisMs,candidates,rej_grayscale,rej_tiny,rej_edge,rej_aspect,
rej_center,rej_radius,rej_fill,rej_shape,rej_disk,radius,trackedRadius
Columns are matched by name, so files with extra or reordered columns still
parse; rows with unparseable numbers are skipped and counted.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from dataclasses import dataclass, field

# Fixed reason columns, in CSV order (SessionTelemetry.REASON_COLUMNS).
REASON_COLUMNS = (
    "grayscale", "tiny", "edge", "aspect", "center",
    "radius", "fill", "shape", "disk",
)

NO_DETECTION = -1.0          # radius/trackRadius column value when nothing was seen
SILENT_SESSION_FRAMES = 300  # >= this many frames with zero detections -> anomaly
SPIKE_ANALYSIS_MS = 50.0     # analysisMs above this counts as a spike
DOMINANT_REJ_SHARE = 0.80    # one reason owning this share of rejections -> anomaly


@dataclass
class Frame:
    """One parsed telemetry row."""
    t_ms: float
    analysis_ms: float
    candidates: int
    rejections: dict  # reason -> count
    radius: float      # -1 when the analyzer saw nothing
    tracked_radius: float  # -1 when the tracker has nothing


@dataclass
class FileResult:
    path: str
    frames: int = 0
    bad_rows: int = 0
    rows: list = field(default_factory=list)


def _to_float(value, default=None):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _to_int(value, default=None):
    f = _to_float(value)
    return int(f) if f is not None and f.is_integer() else default


def parse_file(path):
    """Parse one telemetry CSV. Returns (FileResult); never raises on bad data."""
    result = FileResult(path=path)
    try:
        f = open(path, newline="")
    except OSError as e:
        result.bad_rows = -1  # sentinel: file unreadable
        result.path = f"{path} (unreadable: {e})"
        return result
    with f:
        reader = csv.DictReader(f)
        if reader.fieldnames is None:
            return result  # empty file: 0 frames, no header
        rej_cols = {c for c in (reader.fieldnames or []) if c.startswith("rej_")}
        for row in reader:
            t_ms = _to_float(row.get("tMs"))
            analysis_ms = _to_float(row.get("analysisMs"))
            candidates = _to_int(row.get("candidates"))
            radius = _to_float(row.get("radius"))
            tracked = _to_float(row.get("trackedRadius"))
            if None in (t_ms, analysis_ms, candidates, radius, tracked):
                result.bad_rows += 1
                continue
            rejections = {}
            for col in rej_cols:
                count = _to_int(row.get(col))
                if count is None:
                    break
                rejections[col[4:]] = count
            else:
                result.rows.append(Frame(t_ms, analysis_ms, candidates,
                                        rejections, radius, tracked))
                continue
            result.bad_rows += 1
    result.frames = len(result.rows)
    return result


def _percentile(sorted_vals, pct):
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return sorted_vals[int(k)]
    return sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (k - lo)


def _shrink_rate(frames):
    """Least-squares slope (radius/second) + residual RMS over detected frames."""
    pts = [(fr.t_ms / 1000.0, fr.radius) for fr in frames
           if fr.radius > NO_DETECTION]
    n = len(pts)
    if n < 4:
        return {"samples": n, "slope_per_s": None, "residual_rms": None}
    sx = sum(p[0] for p in pts)
    sy = sum(p[1] for p in pts)
    sxx = sum(p[0] ** 2 for p in pts)
    sxy = sum(p[0] * p[1] for p in pts)
    denom = n * sxx - sx * sx
    if denom == 0:
        return {"samples": n, "slope_per_s": None, "residual_rms": None}
    slope = (n * sxy - sx * sy) / denom
    intercept = (sy - slope * sx) / n
    rms = math.sqrt(sum((p[1] - (slope * p[0] + intercept)) ** 2 for p in pts) / n)
    return {"samples": n, "slope_per_s": slope, "residual_rms": rms}


def summarize(rows):
    """Aggregate a list of Frame into a summary dict."""
    n = len(rows)
    s = {"frames": n}
    if n == 0:
        return s
    t0 = rows[0].t_ms
    t1 = rows[-1].t_ms
    span_s = max((t1 - t0) / 1000.0, 1e-9)
    ams = sorted(fr.analysis_ms for fr in rows)
    s["span_s"] = round(t1 - t0, 1)
    s["fps"] = round(n / span_s, 1)
    s["analysis_ms"] = {
        "mean": round(sum(ams) / n, 2),
        "p95": round(_percentile(ams, 95), 2),
        "max": round(ams[-1], 2),
        "spikes_over_50ms": sum(1 for fr in rows if fr.analysis_ms > SPIKE_ANALYSIS_MS),
    }
    cand = [fr.candidates for fr in rows]
    s["candidates"] = {"mean": round(sum(cand) / n, 1), "max": max(cand)}
    detected = [fr for fr in rows if fr.radius > NO_DETECTION]
    tracked = [fr for fr in rows if fr.tracked_radius > NO_DETECTION]
    s["detection_rate"] = round(len(detected) / n, 4)
    s["tracked_rate"] = round(len(tracked) / n, 4)
    rej_totals = {}
    for fr in rows:
        for reason, count in fr.rejections.items():
            rej_totals[reason] = rej_totals.get(reason, 0) + count
    rej_all = sum(rej_totals.values())
    ordered = sorted(rej_totals.items(), key=lambda kv: kv[1], reverse=True)
    s["rejections"] = {k: v for k, v in ordered}
    s["rejections_total"] = rej_all
    s["dominant_rejection"] = ordered[0][0] if ordered and rej_all else None
    radii = [fr.radius for fr in detected]
    s["radius"] = {
        "detected_frames": len(detected),
        "min": round(min(radii), 4) if radii else None,
        "max": round(max(radii), 4) if radii else None,
        "mean": round(sum(radii) / len(radii), 4) if radii else None,
        **{k: (round(v, 5) if isinstance(v, float) else v)
            for k, v in _shrink_rate(rows).items()},
    }
    anomalies = []
    if n >= SILENT_SESSION_FRAMES and not detected:
        anomalies.append(
            f"silent session: {n} frames, zero detections — ring never "
            "survived the gates (check dominant rejection reason)")
    top = ordered[0] if ordered else (None, 0)
    if rej_all and top[1] / rej_all >= DOMINANT_REJ_SHARE:
        anomalies.append(
            f"rejection dominance: '{top[0]}' owns {top[1]}/{rej_all} "
            f"({top[1] / rej_all:.0%}) of all rejections — prime gate suspect")
    spikes = s["analysis_ms"]["spikes_over_50ms"]
    if spikes:
        anomalies.append(
            f"analysisMs spikes: {spikes} frames over {SPIKE_ANALYSIS_MS:.0f}ms "
            f"(max {s['analysis_ms']['max']}ms)")
    s["anomalies"] = anomalies
    return s


def print_text(summary, label, bad_rows):
    print(f"== {label} ==")
    print(f"frames: {summary['frames']}" +
          (f"  (skipped bad rows: {bad_rows})" if bad_rows else ""))
    if summary["frames"] == 0:
        print("no valid rows")
        return
    print(f"span: {summary['span_s']}s  fps: {summary['fps']}")
    a = summary["analysis_ms"]
    print(f"analysisMs: mean {a['mean']}  p95 {a['p95']}  max {a['max']}  "
          f"spikes>50ms {a['spikes_over_50ms']}")
    c = summary["candidates"]
    print(f"candidates: mean {c['mean']}  max {c['max']}")
    print(f"detection rate: {summary['detection_rate']:.2%}  "
          f"tracked rate: {summary['tracked_rate']:.2%}")
    r = summary["radius"]
    if r["detected_frames"]:
        print(f"radius: min {r['min']}  max {r['max']}  mean {r['mean']}  "
              f"shrink {r['slope_per_s']}/s over {r['samples']} samples"
              + (f" (resid rms {r['residual_rms']})"
                 if r["residual_rms"] is not None else ""))
    print(f"rejections total: {summary['rejections_total']}  "
          f"dominant: {summary['dominant_rejection']}")
    for reason, count in list(summary["rejections"].items())[:5]:
        print(f"  rej_{reason}: {count}")
    if summary["anomalies"]:
        print("anomalies:")
        for note in summary["anomalies"]:
            print(f"  ! {note}")
    print()


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Summarize Throw Assistant session-telemetry CSVs.")
    parser.add_argument("csv", nargs="+", help="session CSV file(s)")
    parser.add_argument("--json", action="store_true",
                        help="print combined summary as JSON instead of text")
    args = parser.parse_args(argv)

    results = [parse_file(p) for p in args.csv]
    all_rows = []
    for r in results:
        all_rows.extend(r.rows)
    if not all_rows:
        print("error: no valid rows parsed from any input", file=sys.stderr)
        return 2

    combined = summarize(all_rows)
    if args.json:
        combined["files"] = len(results)
        combined["bad_rows"] = sum(r.bad_rows for r in results if r.bad_rows > 0)
        print(json.dumps(combined, indent=2, sort_keys=True))
    else:
        for r in results:
            print_text(summarize(r.rows), r.path, r.bad_rows)
        if len(results) > 1:
            print_text(combined, f"COMBINED ({len(results)} files)", 0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
