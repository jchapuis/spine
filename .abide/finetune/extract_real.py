#!/usr/bin/env python3
"""Sample real Scala diffs from projects/*/ git history as an evaluation set (labels filled in later).

    python3 extract_real.py real/candidates.txt --edits 150 --turns 50

Half the samples come from commits that look like review fixes, replayed in reverse (old code becomes the added
lines), so the set holds real violations and not only clean code. Which sample is which lives in the manifest, never
in the blocks the labellers see. Stays on this machine.
"""
import argparse, json, random, re, subprocess
from pathlib import Path

PROJECTS = Path(__file__).resolve().parents[2] / "projects"
FIX_LIKE = re.compile(r"review|compliance|gate|\bck\b|fail.?loud|getOrElse|rename|naming|mapN|foldMap|algebra|"
                      r"refactor|dedup|duplicat|extract|factor|cleanup|simplif", re.I)
MAX_EDIT_LINES, MAX_TURN_LINES, MAX_TURN_FILES, MAX_LINE_CHARS = 80, 150, 4, 300


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True).stdout


def commits(repo):
    out = git(repo, "log", "--no-merges", "--format=%H%x09%P%x09%s", "--", "*.scala")
    for line in out.splitlines():
        sha, parents, subject = (line.split("\t") + ["", ""])[:3]
        if parents and " " not in parents: yield sha, parents, subject


def hunks(repo, a, b, path):
    """The file's hunks from a to b, without the diff --git / index / ---/+++ header lines."""
    lines = git(repo, "diff", "-U3", a, b, "--", path).splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("@@")), None)
    return [] if start is None else lines[start:]


def usable(lines, limit):
    added = [l for l in lines if l.startswith("+") and not l.startswith("+++")]
    return lines and added and len(lines) <= limit and all(len(l) <= MAX_LINE_CHARS for l in lines)


def changed_scala(repo, parent, sha):
    return [p for p in git(repo, "diff", "--name-only", parent, sha, "--", "*.scala").splitlines() if p]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out"); ap.add_argument("--edits", type=int, default=150); ap.add_argument("--turns", type=int, default=50)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    rng = random.Random(a.seed)
    pool = []   # (repo, sha, parent, reversed)
    for repo in sorted(p for p in PROJECTS.iterdir() if (p / ".git").exists()):
        cs = list(commits(repo))
        fixes = [c for c in cs if FIX_LIKE.search(c[2])]
        others = [c for c in cs if not FIX_LIKE.search(c[2])]
        rng.shuffle(fixes); rng.shuffle(others)
        pool += [(repo, s, p, True) for s, p, _ in fixes[:60]] + [(repo, s, p, False) for s, p, _ in others[:60]]
        print(f"{repo.name}: {len(cs)} scala commits, {len(fixes)} fix-like")
    rng.shuffle(pool)
    blocks, manifest, n_edit, n_turn, i = [], [], 0, 0, 0
    for repo, sha, parent, rev in pool:
        if n_edit >= a.edits and n_turn >= a.turns: break
        a_ref, b_ref = (sha, parent) if rev else (parent, sha)
        files = changed_scala(repo, parent, sha)
        want_turn = n_turn < a.turns and (n_edit >= a.edits or rng.random() < 0.3) and 1 < len(files) <= MAX_TURN_FILES
        if want_turn:
            parts = []
            for f in files:
                h = hunks(repo, a_ref, b_ref, f)
                if h: parts += [f"--- a/{f}", f"+++ b/{f}", *h]
            if not usable(parts, MAX_TURN_LINES): continue
            i += 1; n_turn += 1
            blocks.append(f"=== TURN\nlabels: ?\nnote: id=r{i:03d}\n" + "\n".join(parts) + "\n=== END\n")
        elif n_edit < a.edits and files:
            f = rng.choice(files)
            h = hunks(repo, a_ref, b_ref, f)
            if not usable(h, MAX_EDIT_LINES): continue
            i += 1; n_edit += 1
            blocks.append(f"=== EDIT {f}\nlabels: ?\nnote: id=r{i:03d}\n" + "\n".join(h) + "\n=== END\n")
        else:
            continue
        manifest.append({"id": f"r{i:03d}", "project": repo.name, "sha": sha, "reversed": rev})
    out = Path(a.out); out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(blocks))
    out.with_suffix(".manifest.jsonl").write_text("".join(json.dumps(m) + "\n" for m in manifest))
    print(f"{n_edit} edit + {n_turn} turn candidates -> {out} ({sum(m['reversed'] for m in manifest)} reversed fixes)")


if __name__ == "__main__":
    main()
