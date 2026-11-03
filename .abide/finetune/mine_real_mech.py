#!/usr/bin/env python3
"""Mine training examples for the mechanical groups from real project history (gate-labelled afterwards).

    python3 mine_real_mech.py --repos gritmaps endless endless-transaction --out synthetic/real-mech/part-1.txt
    python3 label_mechanical.py 'synthetic/real-mech/part-*.txt' synthetic/real-mech.jsonl

Only repos the owner cleared for a public model (gritmaps, endless, endless-transaction; never the TeraSol-SA ones).
Commits in real/candidates.manifest.jsonl are skipped: they are the held-out real evaluation set.

An agent edit is usually one hunk, so each hunk of a changed Scala file becomes an EDIT: the file before the commit
with only that hunk applied. Each hunk is also written reversed (the commit's fix played backwards gives a real
violation); label_mechanical.py then keeps a reversed hunk only if the gate flags it (see --keep-reversed below).
Commits touching 2-3 Scala files with a small total change also become TURN examples for the cross-file group.
"""
import argparse
import difflib
import json
import random
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECTS = HERE.parents[1] / "projects"
MAX_HUNK_CHANGED = 30
MAX_TURN_CHANGED = 80
MAX_FILE_LINES = 600


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout


def held_out():
    manifest = HERE / "real/candidates.manifest.jsonl"
    return {json.loads(l)["sha"] for l in manifest.read_text().splitlines()}


def commits(repo):
    for line in git(repo, "log", "--no-merges", "--format=%H %P", "--", "*.scala").splitlines():
        parts = line.split()
        if len(parts) == 2:
            yield parts[0], parts[1]


def changed_scala(repo, parent, sha):
    out = git(repo, "diff", "--name-status", "--no-renames", parent, sha, "--", "*.scala")
    return [line.split("\t", 1)[1] for line in out.splitlines() if line[:1] in ("M", "A")]


def lines_at(repo, rev, path):
    out = subprocess.run(["git", "-C", str(repo), "show", f"{rev}:{path}"], capture_output=True, text=True)
    return out.stdout.splitlines() if out.returncode == 0 else []


def hunk_versions(before, after):
    """One 'after' per hunk: before with only that hunk's changes applied."""
    matcher = difflib.SequenceMatcher(a=before, b=after, autojunk=False)
    for group in matcher.get_grouped_opcodes(3):
        changes = [op for op in group if op[0] != "equal"]
        changed = sum(max(i2 - i1, j2 - j1) for _, i1, i2, j1, j2 in changes)
        if changed == 0 or changed > MAX_HUNK_CHANGED:
            continue
        result, cursor = [], 0
        for _, i1, i2, j1, j2 in changes:
            result += before[cursor:i1] + after[j1:j2]
            cursor = i2
        yield result + before[cursor:]


def file_section(path, before, after):
    body = lambda ls: "".join(l + "\n" for l in ls)
    return f"--- file: {path}\n--- before\n{body(before)}--- after\n{body(after)}"


def block(kind, note, sections):
    return f"=== {kind}\nnote: {note}\n" + "".join(sections) + "=== END\n"


def mine(repo_name, skip, rng, per_repo):
    repo = PROJECTS / repo_name
    edits, turns = [], []
    for sha, parent in commits(repo):
        if sha in skip:
            continue
        files = changed_scala(repo, parent, sha)
        snapshots = []
        for path in files:
            before, after = lines_at(repo, parent, path), lines_at(repo, sha, path)
            if not after or len(after) > MAX_FILE_LINES or len(before) > MAX_FILE_LINES:
                continue
            snapshots.append((path, before, after))
            for i, version in enumerate(hunk_versions(before, after)):
                edits.append(block("EDIT", f"{repo_name} {sha[:10]} hunk {i}", [file_section(path, before, version)]))
                if before:
                    edits.append(block("EDIT", f"{repo_name} {sha[:10]} hunk {i} reversed",
                                       [file_section(path, version, before)]))
        if 2 <= len(snapshots) <= 3:
            changed = sum(sum(1 for l in difflib.ndiff(b, a) if l[:1] in "+-") for _, b, a in snapshots)
            if changed <= MAX_TURN_CHANGED:
                turns.append(block("TURN", f"{repo_name} {sha[:10]}", [file_section(*s) for s in snapshots]))
    rng.shuffle(edits)
    return edits[:per_repo], turns


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--repos", nargs="+", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--per-repo", type=int, default=2500, help="max EDIT blocks per repo (forward + reversed)")
    p.add_argument("--seed", type=int, default=7)
    a = p.parse_args()
    rng, skip, blocks = random.Random(a.seed), held_out(), []
    for name in a.repos:
        edits, turns = mine(name, skip, rng, a.per_repo)
        print(f"{name}: {len(edits)} edit blocks, {len(turns)} turn blocks")
        blocks += edits + turns
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(blocks))
    print(f"{len(blocks)} blocks -> {out}")


if __name__ == "__main__":
    main()
