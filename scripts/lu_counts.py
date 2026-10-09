import re, sys, collections
for ID in sys.argv[1:]:
    d="/home/goankur/vsearch/bo/lu/"+ID
    arm={}
    for l in open(d+".pids"):
        p,a=l.split(); arm[p]="B" if (a=="B" or arm.get(p)=="B") else a
    t=open(d+".bpf").read()
    agg=collections.defaultdict(lambda: collections.defaultdict(list))
    for name in ("mincore","willneed"):
        for p,n in re.findall(r"@%s\[(\d+)\]: (\d+)" % name, t):
            if p in arm: agg[arm[p]][name].append(int(n))
    for a in sorted(agg):
        m=agg[a]["mincore"]; w=agg[a]["willneed"]
        print("%s arm=%s jvms=%d mincore/jvm=%.0f (min %d max %d) willneed/jvm=%.0f" % (ID, a, len(m), sum(m)/max(1,len(m)), min(m or [0]), max(m or [0]), sum(w)/max(1,len(w))))
