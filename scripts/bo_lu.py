# PR #16145 backoff A/B on hot wikimedium10m. Usage:
#   bo_lu.py <id> <baseName> <baseCheckout> <baseExtraJvm|-> <candName> <candCheckout> <candExtraJvm|-> <jvmCount>
# The index is always built with arm A so every pair searches the same index.
import sys
import competition
import constants

ARM_A = "/home/goankur/vsearch/bo/lucene-A"
FACETS = (
  ("taxonomy:Date", "Date"), ("taxonomy:Month", "Month"), ("taxonomy:DayOfYear", "DayOfYear"),
  ("sortedset:Date", "Date"), ("sortedset:Month", "Month"), ("sortedset:DayOfYear", "DayOfYear"),
  ("taxonomy:RandomLabel", "RandomLabel"), ("sortedset:RandomLabel", "RandomLabel"),
)

if __name__ == "__main__":
  rid, bn, bc, bx, cn, cc, cx, jvms = sys.argv[1:9]
  comp = competition.Competition(verifyCounts=False, jvmCount=int(jvms), taskRepeatCount=20)
  index = comp.newIndex(ARM_A, competition.sourceData("wikimedium10m"), addDVFields=True, useCMS=True,
                        mergePolicy="TieredMergePolicy", facets=FACETS, numThreads=16)
  def jc(x):
    return constants.JAVA_COMMAND if x == "-" else constants.JAVA_COMMAND + " " + x
  comp.competitor(bn, bc, index=index, searchConcurrency=-1, javaCommand=jc(bx))
  comp.competitor(cn, cc, index=index, searchConcurrency=-1, javaCommand=jc(cx))
  if rid == "INDEXONLY":
    comp.skipSearch()
  comp.benchmark(rid)
