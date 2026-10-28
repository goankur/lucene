# Summarize bo_rr3.sh runs: latency + per-second bpftrace around the 20G -> 10G shrink.
import glob, os, re, sys
# usage: rr3_table.py [results dir] [status file]; defaults are the G6 paths
d = sys.argv[1] if len(sys.argv) > 1 else "/home/goankur/vsearch/bo/rr3/"
status = open(sys.argv[2] if len(sys.argv) > 2 else "/home/goankur/vsearch/bo/rr3_status.txt").read()
print("run\tp50\tp99\tp999\tmax\tmincore/s_pre\tmajflt_0-5s\tmajflt_0-10s\tmajflt_0-30s\tmajflt/s_30s-to-end\tsecs_drop-to-end\twillneed_0-10s")
for log in sorted(glob.glob(d + "T*.log"), key=lambda p: (int(re.search(r"T(\d+)_", p).group(1)), p)):
    L = os.path.basename(log)[:-4]
    t = open(log, errors="ignore").read()
    m = re.search(r"LATENCY_PCT_MS p50=(\S+) p90=\S+ p99=(\S+) p999=(\S+) max=(\S+)", t)
    s = re.search(L + r" SHRINK .*bpf_elapsed_line=(\d+)", status)
    if not (m and s):
        continue
    k = int(s.group(1))  # number of SEC lines printed before the shrink
    rows = [tuple(int(x) for x in re.findall(r"=(\d+)", l)) for l in open(d + L + ".bpf") if l.startswith("SEC ")]
    pre = rows[max(0, k - 20):k]; post = rows[k:]
    f = lambda a, b, i: sum(r[i] for r in post[a:b])
    pre_m = sum(r[2] for r in pre) / max(1, len(pre))
    # the run ends 37-49 s after the drop, so the rate is over the rows that exist after 30 s
    tail = post[30:]
    rate = sum(r[0] for r in tail) / len(tail) if tail else float("nan")
    print("%s\t%s\t%s\t%s\t%s\t%.0f\t%d\t%d\t%d\t%.0f\t%d\t%d" % (L, *m.groups(), pre_m, f(0, 5, 0), f(0, 10, 0), f(0, 30, 0), rate, len(post), f(0, 10, 1)))
