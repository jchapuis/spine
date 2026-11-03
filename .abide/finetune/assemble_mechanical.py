#!/usr/bin/env python3
"""Turn gate-labelled mechanical examples (label_mechanical.py output) into kev training records.

    python3 assemble_mechanical.py synthetic/mech.jsonl synthetic/mech-records.jsonl

Questions come verbatim from mechanical-groups.json (they go into .abide/rubric.json unchanged when the model ships).
Each record carries only the questions abide would ask for that state: the edit groups whose scope matches the
edited file for an EDIT, the turn groups for a TURN. Edit diffs are bare hunks, as abide sends them. Questions are
split across records of at most QUESTIONS_PER_RECORD sharing the state, so the packed request stays under kev's
2,048 tokens; split_data.py keeps records with the same state in one partition.
"""
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
GROUPS = json.loads((HERE / "mechanical-groups.json").read_text())["groups"]
QUESTIONS_PER_RECORD = 4


def glob_regex(glob):
    s = re.escape(glob).replace(r"\*\*/", "\0").replace(r"\*\*", ".*").replace(r"\*", "[^/]*").replace("\0", "(?:.*/)?")
    return re.compile(f"^{s}$")


SCOPES = {g["id"]: [glob_regex(p) for p in g["scope"]] for g in GROUPS}


def in_scope(group, path):
    return any(r.match(path) for r in SCOPES[group["id"]])


def question(group, label):
    q = group["question"]
    return {"type": "noul", "instructions": q["instructions"], "criteria": q["criteria"], "label": bool(label)}


def bare_hunks(diff):
    return "\n".join(l for l in diff.split("\n") if not l.startswith(("--- a/", "+++ b/", "--- /dev/null")))


def records_for(example):
    state = dict(example["state"])
    if example["kind"] == "edit":
        state["diff"] = bare_hunks(state["diff"])
        asked = [g for g in GROUPS if g["when"] == "edit" and in_scope(g, state["file"])]
    else:
        asked = [g for g in GROUPS if g["when"] == "turn" and any(in_scope(g, f) for f in state["files"])]
    if not asked or not any(l.startswith("+") and not l.startswith("+++") for l in state["diff"].split("\n")):
        return []
    chunks = [asked[i:i + QUESTIONS_PER_RECORD] for i in range(0, len(asked), QUESTIONS_PER_RECORD)]
    return [{"state": state, "questions": {g["id"]: question(g, example["labels"][g["id"]]) for g in chunk}}
            for chunk in chunks]


def main(source, out):
    seen, records, skipped, positives, asked = set(), [], Counter(), Counter(), Counter()
    for line in Path(source).read_text().splitlines():
        parts = records_for(json.loads(line))
        if not parts:
            skipped["no question applies or nothing added"] += 1
            continue
        key = hashlib.sha256(parts[0]["state"]["diff"].encode()).hexdigest()
        if key in seen:
            skipped["duplicate diff"] += 1
            continue
        seen.add(key)
        records.extend(parts)
        for r in parts:
            for qid, q in r["questions"].items():
                asked[qid] += 1
                positives[qid] += q["label"]
    Path(out).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in records))
    print(f"{len(records)} records -> {out}")
    for g in GROUPS:
        print(f"  {g['id']:28} asked {asked[g['id']]:5}  positive {positives[g['id']]:4}")
    if skipped:
        print("skipped:", dict(skipped))


if __name__ == "__main__":
    main(*sys.argv[1:3])
