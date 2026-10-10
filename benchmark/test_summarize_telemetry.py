#!/usr/bin/env python3
"""Self-contained checks for summarize_telemetry.py (no pytest needed).

    python3 test_summarize_telemetry.py

Exit 0 when every check passes, exit 1 on the first failure.
"""

import json
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from summarize_telemetry import (  # noqa: E402
    parse_file, summarize, main, REASON_COLUMNS, diff_summaries,
)

HEADER = ("tMs,analysisMs,candidates,"
          + ",".join("rej_" + r for r in REASON_COLUMNS)
          + ",radius,trackedRadius")


def write_csv(rows):
    """rows: list of dicts keyed by header name (missing -> 0)."""
    f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False)
    f.write(HEADER + "\n")
    for r in rows:
        f.write(",".join(str(r.get(c, 0)) for c in HEADER.split(",")) + "\n")
    f.close()
    return f.name


def check(name, cond, detail=""):
    if not cond:
        raise AssertionError(f"{name}: FAILED {detail}")
    print(f"ok - {name}")


def frame(t, radius=-1.0, tracked=-1.0, analysis=8.0, cand=3, **rej):
    return {
        "tMs": t, "analysisMs": analysis, "candidates": cand,
        "radius": radius, "trackedRadius": tracked,
        **{"rej_" + k: v for k, v in rej.items()},
    }


# 1. Happy path: 600 frames, ~60fps, shrinking radius, fill-dominated rejections
rows = []
for i in range(600):
    t = round(i * 1000 / 60, 3)
    r = 0.09 - 0.006 * (t / 1000) if 100 <= i <= 500 else -1.0
    tr = r if i > 120 else -1.0
    ams = 8.0 + (60.0 if i % 200 == 199 else 0.0)  # 3 spikes over 50ms
    rows.append(frame(t, radius=round(r, 6), tracked=round(tr, 6), analysis=ams,
                      fill=6, shape=1, tiny=0))
path = write_csv(rows)
res = parse_file(path)
check("happy parse frames", res.frames == 600, res.frames)
check("happy parse bad_rows", res.bad_rows == 0, res.bad_rows)
s = summarize(res.rows)
check("detection rate", s["detection_rate"] == round(401 / 600, 4),
      s["detection_rate"])
check("tracked rate", s["tracked_rate"] == round(380 / 600, 4),
      s["tracked_rate"])
check("dominant rejection", s["dominant_rejection"] == "fill",
      s["dominant_rejection"])
check("rej totals", s["rejections"]["fill"] == 3600
      and s["rejections"]["shape"] == 600, s["rejections"])
check("fps sane", 55 < s["fps"] < 65, s["fps"])
check("analysis spikes", s["analysis_ms"]["spikes_over_50ms"] == 3,
      s["analysis_ms"]["spikes_over_50ms"])
r = s["radius"]
check("shrink slope", abs(r["slope_per_s"] - (-0.006)) < 0.0005,
      r["slope_per_s"])
check("shrink samples", r["samples"] == 401, r["samples"])
check("radius min/max", 0.039 < r["min"] < 0.041 and 0.079 < r["max"] < 0.081,
      (r["min"], r["max"]))
check("no silent anomaly", not any("silent" in a for a in s["anomalies"]),
      s["anomalies"])
check("dominance anomaly", any("fill" in a and "rejection dominance" in a
                              for a in s["anomalies"]), s["anomalies"])
check("spike anomaly", any("spikes" in a for a in s["anomalies"]),
      s["anomalies"])
os.unlink(path)

# 2. Silent session -> anomaly
path2 = write_csv([frame(i * 16.7, radius=-1.0, tracked=-1.0, fill=4)
                   for i in range(400)])
s2 = summarize(parse_file(path2).rows)
check("silent rate", s2["detection_rate"] == 0.0, s2["detection_rate"])
check("silent anomaly", any("silent session" in a for a in s2["anomalies"]),
      s2["anomalies"])
os.unlink(path2)

# 3. Bad rows are skipped and counted; unparseable file-level rows ignored
f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False)
f.write(HEADER + "\n")
f.write("10,8.0,3,0,0,0,0,0,0,0,0,0,-1,-1\n")          # valid
f.write("garbage,line,with,too,few,columns\n")           # bad
f.write("20,notanumber,3,0,0,0,0,0,0,0,0,0,-1,-1\n")     # bad
f.write("30,8.0,3,0,0,0,0,0,0,0,0,0,0.08,0.08\n")        # valid
f.close()
res3 = parse_file(f.name)
check("bad rows counted", res3.bad_rows == 2, res3.bad_rows)
check("good rows kept", res3.frames == 2, res3.frames)
check("bad row radius", summarize(res3.rows)["detection_rate"] == 0.5)
os.unlink(f.name)

# 4. Empty file -> zero rows, no crash
f4 = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False)
f4.close()
res4 = parse_file(f4.name)
check("empty parse", res4.frames == 0 and res4.bad_rows == 0, res4)
s4 = summarize(res4.rows)
check("empty summarize", s4["frames"] == 0 and "anomalies" not in s4)
check("empty main exit 2", main([f4.name]) == 2)
os.unlink(f4.name)

# 5. Missing file -> main exits 2, does not raise
check("missing file exit 2", main(["/no/such/file.csv"]) == 2)

# 6. --json output parses and carries the fields
path6 = write_csv([frame(i * 16.7, radius=0.05, tracked=0.05, tiny=2)
                   for i in range(10)])
proc = subprocess.run([sys.executable,
                       os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "summarize_telemetry.py"),
                       "--json", path6], capture_output=True, text=True)
check("json exit 0", proc.returncode == 0, proc.stderr)
j = json.loads(proc.stdout)
for key in ("frames", "detection_rate", "radius", "rejections", "anomalies"):
    check(f"json has {key}", key in j, j.keys())
check("json frames", j["frames"] == 10, j["frames"])
os.unlink(path6)

# 7. Extra columns and reordered header still parse (column matching by name)
f7 = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False)
f7.write("radius,tMs,foo,trackedRadius,analysisMs,candidates,rej_tiny\n")
f7.write("0.05,0,bar,-1,8.0,2,5\n")
f7.write("-1,16.7,bar,-1,8.0,2,5\n")
f7.close()
res7 = parse_file(f7.name)
check("reordered parse", res7.frames == 2, res7.frames)
check("reordered detection", summarize(res7.rows)["detection_rate"] == 0.5)
os.unlink(f7.name)

# 8. --vs diffs target against baseline
base_p = write_csv([frame(i * 16.7, radius=0.05 if i % 3 == 0 else -1.0,
                          tracked=0.05 if i % 3 == 0 else -1.0, fill=6, shape=1)
                    for i in range(90)])
tgt_p = write_csv([frame(i * 16.7, radius=0.05 if i % 2 == 0 else -1.0,
                         tracked=0.05 if i % 2 == 0 else -1.0, fill=1, shape=6)
                   for i in range(90)])
b = summarize(parse_file(base_p).rows)
t = summarize(parse_file(tgt_p).rows)
d = diff_summaries(b, t)
check("diff detection delta positive", d["detection_rate"]["delta"] > 0,
      d["detection_rate"])
check("diff detection delta value",
      abs(d["detection_rate"]["delta"]
          - (round(45 / 90, 4) - round(30 / 90, 4))) < 1e-9,
      d["detection_rate"]["delta"])
check("diff fill delta negative", d["rejections"]["fill"]["delta"] < 0,
      d["rejections"]["fill"])
check("diff shape delta positive", d["rejections"]["shape"]["delta"] > 0,
      d["rejections"]["shape"])
dr = d["dominant_rejection"]
check("diff dominant changed", dr["changed"] and dr["base"] == "fill"
      and dr["target"] == "shape", dr)
check("diff frames triple", d["frames"] == {"base": 90, "target": 90,
                                            "delta": 0}, d["frames"])

# silent baseline vs detecting target: radius mean delta must be None, not 0
silent_p = write_csv([frame(i * 16.7, radius=-1.0, tracked=-1.0, tiny=3)
                      for i in range(10)])
d2 = diff_summaries(summarize(parse_file(silent_p).rows), t)
check("diff None radius delta",
      d2["radius"]["mean"]["delta"] is None, d2["radius"]["mean"])
check("diff detected frames delta",
      d2["radius"]["detected_frames"]["delta"] == 45 - 0,
      d2["radius"]["detected_frames"])

# CLI: --vs prints a DIFF block; --vs with empty baseline exits 2
script = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      "summarize_telemetry.py")
proc = subprocess.run([sys.executable, script, tgt_p, "--vs", base_p],
                      capture_output=True, text=True)
check("vs exit 0", proc.returncode == 0, proc.stderr)
check("vs DIFF block",
      "DIFF" in proc.stdout and "dominant rej:" in proc.stdout
      and "(CHANGED)" in proc.stdout, proc.stdout[:400])
proc_j = subprocess.run([sys.executable, script, "--json", tgt_p,
                         "--vs", base_p], capture_output=True, text=True)
j3 = json.loads(proc_j.stdout)
check("json diff embedded",
      "diff" in j3 and j3["diff"]["dominant_rejection"]["changed"] is True)
check("json diff rej delta",
      j3["diff"]["rejections"]["fill"]["delta"] < 0)
f_empty = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False)
f_empty.close()
proc_e = subprocess.run([sys.executable, script, tgt_p, "--vs",
                         f_empty.name], capture_output=True, text=True)
check("vs empty baseline exit 2", proc_e.returncode == 2, proc_e.stderr)
os.unlink(base_p)
os.unlink(tgt_p)
os.unlink(silent_p)
os.unlink(f_empty.name)

print("\nALL CHECKS PASSED")
