#!/usr/bin/env python3
"""Fill `labels: ?` in the candidate blocks from two independent labellers; disagreements need an explicit decision.

    python3 merge_labels.py real/candidates.txt real/labels-A.txt real/labels-B.txt real/labelled.txt r115:dup=1 r195:dup=1
"""
import re, sys
from pathlib import Path


def read(path):
    return {line.split()[0]: " ".join(line.split()[1:]) for line in Path(path).read_text().splitlines() if line.strip()}


candidates, a_path, b_path, out, *decisions = sys.argv[1:]
a, b = read(a_path), read(b_path)
decided = {}
for d in decisions:
    rid, rest = d.split(":", 1)
    decided[rid] = rest
merged, open_ = {}, []
for rid in a:
    if a[rid] == b.get(rid): merged[rid] = a[rid]
    elif rid in decided:
        labels = dict(kv.split("=") for kv in a[rid].split())
        labels.update(dict(kv.split("=") for kv in decided[rid].split()))
        merged[rid] = " ".join(f"{k}={v}" for k, v in labels.items())
    else: open_.append(f"{rid}: A[{a[rid]}] B[{b.get(rid)}]")
if open_: sys.exit("undecided disagreements:\n" + "\n".join(open_))
text = re.sub(r"labels: \?\nnote: id=(r\d+)", lambda m: f"labels: {merged[m.group(1)]}\nnote: id={m.group(1)}", Path(candidates).read_text())
Path(out).write_text(text)
print(f"{len(merged)} blocks labelled ({sum(1 for r in a if a[r] != b.get(r))} decided by hand) -> {out}")
