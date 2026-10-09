import re, sys
tasks=["AndHighLow","LowTerm","OrHighLow","MedTerm","AndHighMed","OrHighMed","HighTermTitleSort","Prefix3","PKLookup","Wildcard"]
print("pair\t"+"\t".join(tasks))
for ID in sys.argv[1:]:
    t=open("/home/goankur/vsearch/bo/lu/%s.log" % ID, errors="ignore").read()
    t=t[t.rfind("Report after iter 19"):]
    row=[]
    for k in tasks:
        m=re.search(r"^\s*%s\s+\S+\s+\(\s*\S+%%\)\s+\S+\s+\(\s*\S+%%\)\s+(\S+)%%.*?(\S+)$" % k, t, re.M)
        row.append("%s(p%s)" % (m.group(1), m.group(2)) if m else "?")
    print(ID+"\t"+"\t".join(row))
