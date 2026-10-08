"""Benchmark harness: runs the Python port of ScreenAnalyzer over corpus.json.

Scores detection rate + rejection breakdown per case. Exits non-zero when any
case fails its expectation — usable as a gate before tuning detector gates.

Usage: python3 run_benchmark.py [--save corpus-out/]
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyzer_port import analyze
from generate_corpus import make_case, W, H
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))


def load_pixels(img):
    img = img.convert("RGB")
    w, h = img.size
    return [(r, g, b) for (r, g, b) in img.getdata()], w, h


def score_case(case, tol):
    if case["kind"] == "synthetic":
        img = make_case(case)
        exp = case.get("ring")
    else:
        img = Image.open(os.path.join(HERE, case["file"]))
        exp = case["ring"]
    pixels, w, h = load_pixels(img)
    t0 = time.time()
    ring, stats = analyze(pixels, w, h)
    ms = (time.time() - t0) * 1000

    expect = case["expect"]
    if expect == "reject":
        ok = ring is None
        detail = "correctly rejected" if ok else f"false positive at {ring}"
    else:
        if ring is None:
            ok, detail = False, "missed (null)"
        else:
            dx = abs(ring[0] - exp["cx"])
            dy = abs(ring[1] - exp["cy"])
            dr = abs(ring[2] - exp["radius_frac"]) / exp["radius_frac"]
            ok = dx <= tol["center_frac"] and dy <= tol["center_frac"] and dr <= tol["radius_rel"]
            detail = (f"center_err=({dx:.3f},{dy:.3f}) radius_rel_err={dr:.2f} "
                      f"conf={ring[3]:.2f}") if ok else \
                     (f"OFF center_err=({dx:.3f},{dy:.3f}) radius_rel_err={dr:.2f} conf={ring[3]:.2f}")
    return {"id": case["id"], "expect": expect, "ok": ok, "detail": detail,
            "ms": round(ms, 1), "stats": stats}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--save", default=None, help="directory to dump annotated results")
    args = ap.parse_args()
    corpus = json.load(open(os.path.join(HERE, "corpus.json")))
    results = []
    for case in corpus["cases"]:
        tol = corpus["tolerances"][case["kind"]]
        results.append(score_case(case, tol))

    fails = [r for r in results if not r["ok"]]
    print(f"{'CASE':34} {'EXPECT':7} {'OK?':4} {'MS':>7}  DETAIL / REJECTIONS")
    for r in results:
        rej = r["stats"]["rejected"]
        rej_s = ",".join(f"{k}:{v}" for k, v in sorted(rej.items())) if rej else "-"
        print(f"{r['id']:34} {r['expect']:7} {'PASS' if r['ok'] else 'FAIL':4} "
              f"{r['ms']:7.1f}  {r['detail']}  [{rej_s}]")

    n = len(results)
    by_kind = {}
    for r in results:
        k = "pos" if r["expect"] == "detect" else "neg"
        by_kind.setdefault(k, [0, 0])
        by_kind[k][1] += 1
        by_kind[k][0] += r["ok"]
    print(f"\nTOTAL {n - len(fails)}/{n} pass | "
          f"positives {by_kind.get('pos', [0, 0])[0]}/{by_kind.get('pos', [0, 0])[1]} detected | "
          f"negatives {by_kind.get('neg', [0, 0])[0]}/{by_kind.get('neg', [0, 0])[1]} correctly rejected")
    agg_rej = {}
    for r in results:
        if r["expect"] == "detect" and not r["ok"]:
            for k, v in r["stats"]["rejected"].items():
                agg_rej[k] = agg_rej.get(k, 0) + v
    if agg_rej:
        print("Rejection causes on missed positives:", agg_rej)

    if args.save:
        os.makedirs(args.save, exist_ok=True)
        json.dump(results, open(os.path.join(args.save, "results.json"), "w"), indent=1)
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
