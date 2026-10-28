# Summarize transition_run.sh: latency plus per-second bpftrace around the 20 GB -> 10 GB cgroup drop.
import glob, os, re
d = "/home/goankur/vsearch/bo/transition/"
status = open("/home/goankur/vsearch/bo/transition_status.txt").read()
print("run\tp50\tp99\tp999\tmincore/s before drop\tmajor faults 0-10 s after\tmajor faults 0-30 s after\tmajor faults/s from 30 s to end\tseconds from drop to end\tQPS before drop")
for log in sorted(glob.glob(d + "*.log")):
    L = os.path.basename(log)[:-4]
    t = open(log, errors="ignore").read()
    m = re.search(r"LATENCY_PCT_MS p50=(\S+) p90=\S+ p99=(\S+) p999=(\S+)", t)
    s = re.search(re.escape(L) + r" SHRINK .*?([\d.]+) qps .*bpf_elapsed_line=(\d+)", status)
    if not (m and s):
        continue
    k = int(s.group(2))
    rows = [tuple(int(x) for x in re.findall(r"=(\d+)", l)) for l in open(d + L + ".bpf") if l.startswith("SEC ")]
    pre, post = rows[max(0, k - 15):k - 2], rows[k:]
    f = lambda a, b: sum(r[0] for r in post[a:b])
    tail = post[30:]
    rate = sum(r[0] for r in tail) / len(tail) if tail else float("nan")
    print("%s\t%s\t%s\t%s\t%.0f\t%d\t%d\t%.0f\t%d\t%s" % (L, *m.groups(), sum(r[2] for r in pre) / max(1, len(pre)), f(0, 10), f(0, 30), rate, len(post), s.group(1)))
