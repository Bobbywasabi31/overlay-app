#!/usr/bin/env python3
"""Self-contained checks for run_benchmark.py --json (no pytest needed).

    python3 test_run_benchmark.py

Exit 0 when every check passes, exit 1 on the first failure.
"""

import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from run_benchmark import build_report  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKS = []


def check(name):
    def deco(fn):
        CHECKS.append((name, fn))
        return fn
    return deco


def case(cid, expect, ok, rejected=None):
    return {"id": cid, "expect": expect, "ok": ok,
            "detail": "detail", "ms": 1.5,
            "stats": {"rejected": rejected or {}}}


@check("summary counts positives, negatives, and passes")
def _():
    r = build_report([case("a", "detect", True),
                      case("b", "detect", False, {"mask": 3}),
                      case("c", "reject", True)])
    s = r["summary"]
    assert s["total"] == 3, s
    assert s["passed"] == 2, s
    assert s["positives_detected"] == 1 and s["positives_total"] == 2, s
    assert s["negatives_correct"] == 1 and s["negatives_total"] == 1, s
    assert s["missed_positive_rejection_causes"] == {"mask": 3}, s


@check("missed negatives do not join the rejection-cause rollup")
def _():
    r = build_report([case("c", "reject", False, {"gate": 1})])
    s = r["summary"]
    assert s["missed_positive_rejection_causes"] == {}, s
    assert s["passed"] == 0 and s["total"] == 1, s


@check("payload is JSON round-trippable")
def _():
    r = build_report([case("a", "detect", True, {"mask": 2})])
    text = json.dumps(r, indent=2, sort_keys=True)
    back = json.loads(text)
    assert back["results"][0]["id"] == "a"
    assert back["summary"]["passed"] == 1


@check("cli --json prints pure JSON and exits 0 on the real corpus")
def _():
    p = subprocess.run([sys.executable, os.path.join(HERE, "run_benchmark.py"), "--json"],
                       capture_output=True, text=True, timeout=300)
    assert p.returncode == 0, p.stderr[-500:]
    payload = json.loads(p.stdout)  # raises if stdout is not pure JSON
    n = len(payload["results"])
    assert n == 22, n
    assert payload["summary"]["total"] == 22, payload["summary"]
    assert payload["summary"]["passed"] == 22, payload["summary"]
    assert "TOTAL" not in p.stdout


@check("cli default output keeps the human table")
def _():
    p = subprocess.run([sys.executable, os.path.join(HERE, "run_benchmark.py")],
                       capture_output=True, text=True, timeout=300)
    assert p.returncode == 0, p.stderr[-500:]
    assert "TOTAL 22/22 pass" in p.stdout


def main():
    failed = 0
    for name, fn in CHECKS:
        try:
            fn()
        except Exception as e:  # noqa: BLE001 - report then continue
            failed += 1
            print(f"FAIL {name}: {type(e).__name__}: {e}")
        else:
            print(f"ok   {name}")
    print(f"\n{len(CHECKS) - failed}/{len(CHECKS)} checks passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
