#!/usr/bin/env python3
"""compare_fe.py <results-dir>: JMH results for main, pr-head and fault-epoch; deltas are vs main."""
import json, os, re, sys
R = sys.argv[1]
V = ["main", "pr-head", "fault-epoch"]
def load(p):
    out = {}
    try:
        rows = json.load(open(p))
    except ValueError:
        return None  # still being written
    for r in rows:
        n = r["benchmark"].split(".")[-1]
        ps = ",".join(f"{k}={v}" for k, v in sorted(r.get("params", {}).items()) if k in ("topK",))
        out[n + (f" [{ps}]" if ps else "")] = (r["primaryMetric"]["score"], r["primaryMetric"]["scoreError"])
    return out
def sk(k):
    m = re.search(r"_T(\d+)", k); return (re.sub(r"_T\d+.*", "", k), int(m.group(1)) if m else 0, k)
for scen in ("hot", "pressure", "cold"):
    for h in ("randomread", "storedfields"):
        d = {v: load(f) for v in V if os.path.exists(f := os.path.join(R, f"{v}-{scen}-{h}.json"))}
        d = {v: x for v, x in d.items() if x is not None}
        if "main" not in d: continue
        print(f"\n### {scen} / {h} (ops/ms, higher is better; deltas vs main)\n")
        print("| benchmark | main | " + " | ".join(v for v in V[1:] if v in d) + " |")
        print("|---|---:|" + "---:|" * len([v for v in V[1:] if v in d]))
        for k in sorted(d["main"], key=sk):
            a, ae = d["main"][k]
            cells = []
            for v in V[1:]:
                if v not in d or k not in d[v]: continue
                b, be = d[v][k]
                cells.append(f"{b:,.1f} ({(b - a) / a * 100:+.1f}%)" if a else f"{b:,.1f}")
            print(f"| {k} | {a:,.1f} ± {ae:,.1f} | " + " | ".join(cells) + " |")
