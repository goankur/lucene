# Attribute WILLNEED madvise calls to index files via /proc/pid/maps snapshots. Usage: wn_attr.py <ID>
import sys, os, re, bisect, collections
ID=sys.argv[1]; d="/home/goankur/vsearch/bo/lu/"
arm={}
for l in open(d+ID+".pids"):
    p,a=l.split()
    if arm.get(p)!="B": arm[p]=a
maps={}
for f in os.listdir(d+ID+".maps"):
    if not f.isdigit(): continue
    rows=[]
    for l in open(d+ID+".maps/"+f):
        x=l.split()
        lo,hi=(int(v,16) for v in x[0].split("-")); rows.append((lo,hi,os.path.basename(x[-1])))
    rows.sort(); maps[f]=rows
by=collections.defaultdict(collections.Counter); un=collections.Counter()
for l in open(d+ID+".bpf"):
    if not l.startswith("WN "): continue
    _,p,a,n=l.split(); a=int(a,16)
    rows=maps.get(p)
    if not rows: un[arm.get(p,"?")]+=1; continue
    i=bisect.bisect_right([r[0] for r in rows],a)-1
    if i>=0 and rows[i][0]<=a<rows[i][1]:
        name=rows[i][2]; ext=name.rsplit(".",1)[-1]
        by[arm.get(p,"?")][ext]+=1
    else: un[arm.get(p,"?")]+=1
for a in sorted(by):
    jv=sum(1 for p in maps if arm.get(p)==a)
    print(a, "jvms_with_maps=%d" % jv, "unattributed=%d" % un[a], " ".join("%s:%d" % kv for kv in by[a].most_common(12)))
