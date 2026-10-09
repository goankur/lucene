import glob, re, os
print("run\tp50\tp90\tp99\tp999\trecall\twillneed/q\tmincore/q\tmajflt/q\tr/s\trareq_kB\taqu\tMB/s")
def key(p):
    b=os.path.basename(p)[:-4]; return (b.split("_r")[1], b)
for p in sorted(glob.glob("/home/goankur/vsearch/bo/rr/F[12]0_*.log"), key=key):
    b=p[:-4]; t=open(p, errors="ignore").read()
    m=re.search(r"LATENCY_PCT_MS p50=(\S+) p90=(\S+) p99=(\S+) p999=(\S+)", t)
    if not m: continue
    rec=re.search(r"^SUMMARY: (\S+)", t, re.M); rec=rec.group(1) if rec else "?"
    bp=open(b+".bpf").read() if os.path.exists(b+".bpf") else ""
    c={k:int(v) for k,v in re.findall(r"@(\w+): (\d+)", bp)}
    q=10000
    rows=[l.split() for l in open(b+".iostat") if l.startswith("nvme1n1")]
    rows=[r for r in rows if float(r[1])>0][1:-1]
    n=max(1,len(rows))
    rs=sum(float(r[1]) for r in rows)/n; kb=sum(float(r[2]) for r in rows)/n
    aqu=sum(float(r[-2]) for r in rows)/n
    print("%s\t%s\t%s\t%s\t%s\t%s\t%.1f\t%.1f\t%.1f\t%.0f\t%.2f\t%.2f\t%.0f" % (os.path.basename(b), *m.groups(), rec,
          c.get("madvise_willneed",0)/q, c.get("mincore",0)/q, c.get("major_faults",0)/q, rs, kb/max(rs,1), aqu, kb/1024))
