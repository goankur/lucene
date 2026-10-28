# Count query types with p < 0.05 in the last luceneutil QPS table ("Report after iter 19").
import re, sys
for ID in sys.argv[1:]:
    t = open("/home/goankur/vsearch/bo/lu/%s.log" % ID, errors="ignore").read()
    t = t[t.rfind("Report after iter 19"):]
    t = t[t.find("Pct diff p-value"):]
    rows = re.findall(r"^\s*(\S+)\s+[\d.]+\s+\(\s*[\d.]+%\)\s+[\d.]+\s+\(\s*[\d.]+%\)\s+(-?[\d.]+)%\s+\(.*?\)\s+([\d.]+)$", t, re.M)
    slow = sorted((float(d), k) for k, d, p in rows if float(p) < 0.05 and float(d) < 0)
    fast = [(float(d), k) for k, d, p in rows if float(p) < 0.05 and float(d) > 0]
    print("%s\ttypes=%d\tslower=%d\tfaster=%d\tworst=%s" % (ID, len(rows), len(slow), len(fast), ", ".join("%s %+.1f%%" % (k, d) for d, k in slow[:4])))
