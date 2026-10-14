#!/usr/bin/env python3
"""Rebuild the real evaluation diffs as whole before/after files, for gate-labelling the mechanical groups.

    python3 build_real_mech.py                      # real/candidates.txt + manifest -> real/mech-real.txt
    python3 label_mechanical.py real/mech-real.txt real/mech-real.jsonl

Same 200 diffs as the judgment real-code check (extract_real.py), read back from projects/*/ history. Output stays in
real/, which is gitignored and never leaves this machine.
"""
import json
import re
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROJECTS = HERE.parents[1] / "projects"


def git_show(repo, rev, path):
    out = subprocess.run(["git", "-C", str(repo), "show", f"{rev}:{path}"], capture_output=True, text=True)
    return out.stdout if out.returncode == 0 else ""


def candidate_paths(text):
    for m in re.finditer(r"^=== (EDIT|TURN)[ \t]*(.*?)\n(.*?)^=== END", text, re.M | re.S):
        kind, path, body = m.groups()
        if kind == "EDIT":
            yield "EDIT", [path.strip()]
        else:
            yield "TURN", re.findall(r"^\+\+\+ b/(\S+)", body, re.M)


def main():
    manifest = [json.loads(l) for l in (HERE / "real/candidates.manifest.jsonl").read_text().splitlines()]
    candidates = list(candidate_paths((HERE / "real/candidates.txt").read_text()))
    if len(manifest) != len(candidates):
        raise SystemExit(f"manifest has {len(manifest)} entries but candidates.txt has {len(candidates)} blocks")
    blocks, missing = [], 0
    for entry, (kind, paths) in zip(manifest, candidates):
        repo = PROJECTS / entry["project"]
        old, new = f"{entry['sha']}^", entry["sha"]
        if entry["reversed"]:
            old, new = new, old
        files = []
        for path in paths:
            before, after = git_show(repo, old, path), git_show(repo, new, path)
            if not after:
                missing += 1
                continue
            files.append(f"--- file: {path}\n--- before\n{before}--- after\n{after}")
        if files:
            blocks.append(f"=== {kind}\nnote: {entry['id']} {entry['project']}\n" + "".join(files) + "=== END\n")
    (HERE / "real/mech-real.txt").write_text("".join(blocks))
    print(f"{len(blocks)} blocks -> real/mech-real.txt ({missing} files missing after the change)")


if __name__ == "__main__":
    main()
