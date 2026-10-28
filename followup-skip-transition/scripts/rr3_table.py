# Summarize bo_rr3.sh runs: latency + per-second bpftrace around the 20G -> 10G shrink.
import glob, os, re, sys
d = "/home/goankur/vsearch/bo/rr3/"
status = open("/home/goankur/vsearch/bo/rr3_status.txt").read()
print("run\tp50\tp99\tp999\tmax\tmincore/s_pre\tmajflt_0-5s\tmajflt_0-10s\tmajflt_0-30s\tmajflt/s_30-60s\twillneed_0-10s")
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
    print("%s\t%s\t%s\t%s\t%s\t%.0f\t%d\t%d\t%d\t%.0f\t%d" % (L, *m.groups(), pre_m, f(0, 5, 0), f(0, 10, 0), f(0, 30, 0), f(30, 60, 0) / 30.0, f(0, 10, 1)))
