#!/usr/bin/env python3
"""Turn labelled text blocks (see SPEC.md) into kev training records.

    python3 assemble.py 'synthetic/raw/*/part-*.txt' synthetic/all.jsonl

Each record has the state abide sends ({file, diff} for an edit, {files, diff} for a turn) and the rubric's questions
copied verbatim from .abide/rubric.json, plus a label. Fine-tuning binds those exact strings, so they must match.
"""
import glob, hashlib, json, re, sys
from collections import Counter
from pathlib import Path

RUBRIC = Path(__file__).resolve().parents[2] / ".abide/rubric.json"
KEYS = {
    "fail-loud": "fail-loud-no-silent-fallback",
    "service-dep": "server-architecture-service-depends-on-service",
    "logic": "server-architecture-logic-placement",
    "names": "scala-plain-readable-names",
    "cats": "cats-right-combinator",
    "dup": "factor-out-within-diff-duplication",
}
EDIT_KEYS = {"fail-loud", "service-dep", "logic", "names", "cats"}
TURN_KEYS = {"dup"}
DIFF_LINE = re.compile(r"^(@@|\+\+\+ |--- | |\+|-)")


def questions():
    rules = {r["id"]: r["check"]["question"] for r in json.loads(RUBRIC.read_text())["rules"]}
    return {rid: {"type": "noul", "instructions": q["instructions"], "criteria": q["criteria"]} for rid, q in rules.items()}


def blocks(text):
    for m in re.finditer(r"^=== (EDIT|TURN)[ \t]*(.*?)\n(.*?)^=== END[ \t]*$", text, re.M | re.S):
        yield m.group(1), m.group(2).strip(), m.group(3)


def parse(kind, path, body):
    lines = body.split("\n")
    labels_line = next((l for l in lines if l.startswith("labels:")), None)
    if labels_line is None: return None, "no labels line"
    labels = {}
    for k, v in re.findall(r"([a-z-]+)\s*=\s*([01])", labels_line): labels[k] = v == "1"
    want = EDIT_KEYS if kind == "EDIT" else TURN_KEYS
    if set(labels) != want: return None, f"labels {sorted(labels)} != {sorted(want)}"
    diff = [l for l in lines if not l.startswith(("labels:", "note:"))]
    while diff and not diff[-1].strip(): diff.pop()
    while diff and not diff[0].strip(): diff.pop(0)
    diff = [l if l else " " for l in diff]   # a blank context line is a single space in a real diff
    if not diff: return None, "empty diff"
    if any(not DIFF_LINE.match(l) for l in diff): return None, "non-diff line"
    if not any(l.startswith("+") and not l.startswith("+++") for l in diff): return None, "nothing added"
    text = "\n".join(diff)
    if kind == "EDIT":
        if not path or not diff[0].startswith("@@"): return None, "edit needs a path and to start with @@"
        state = {"file": path, "diff": text}
    else:
        files = [l[len("+++ b/"):] for l in diff if l.startswith("+++ b/")]
        if not files or not diff[0].startswith("--- a/"): return None, "turn needs ---/+++ file headers"
        state = {"files": files, "diff": text}
    return (state, {KEYS[k]: v for k, v in labels.items()}), None


def main(pattern, out):
    qs = questions()
    seen, records, rejected, pos, kinds = set(), [], Counter(), Counter(), Counter()
    for f in sorted(glob.glob(pattern)):
        for kind, path, body in blocks(Path(f).read_text()):
            parsed, why = parse(kind, path, body)
            if why: rejected[why] += 1; continue
            state, labels = parsed
            h = hashlib.sha256(state["diff"].encode()).hexdigest()
            if h in seen: rejected["duplicate diff"] += 1; continue
            seen.add(h)
            kinds[kind] += 1
            for rid, v in labels.items(): pos[rid] += v
            records.append({"state": state, "questions": {rid: {**qs[rid], "label": v} for rid, v in labels.items()}})
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w") as w:
        for r in records: w.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{len(records)} records -> {out}  ({dict(kinds)})")
    print("positives:", {k: pos[v] for k, v in KEYS.items()})
    if rejected: print("rejected:", dict(rejected))


if __name__ == "__main__":
    main(*sys.argv[1:3])
