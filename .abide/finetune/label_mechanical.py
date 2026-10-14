"""Label synthetic examples for the mechanical-rule groups by running the compliance gate on them.

    python3 label_mechanical.py 'synthetic/mech/*/part-*.txt' synthetic/mech.jsonl
    python3 label_mechanical.py --fixtures        # check the labeller against the gate's own fixtures

Input blocks (whole files, so the gate's Scalafix rules can parse them):

    === EDIT                      (one file; === TURN for a whole change over several files)
    note: free text
    --- file: server/src/main/scala/com/acme/Foo.scala
    --- before
    ...file before the change, empty for a new file...
    --- after
    ...file after the change...
    === END

The generator writes no labels. Each example gets its own scratch git repo (the gate's duplication rules compare
across files, so examples must not see each other); the gate runs on it, and a group is 1 when one of its rules
fires on a line the change adds. Rules that aggregate across a file count when the file changed at all.
"""

import difflib
import glob
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPINE = HERE.parent.parent
GATE = SPINE / ".claude/skills/compliance-gate/bin/compliance-gate"
FIXTURES = SPINE / ".claude/skills/compliance-gate/fixtures"
GROUPS = json.loads((HERE / "mechanical-groups.json").read_text())["groups"]
GROUP_OF = {rule: g["id"] for g in GROUPS for rule in g["rules"]}
AGGREGATED = {"factor-out.repeated-numeric-literal", "factor-out.duplicate-import-block"}
PARALLEL = int(os.environ.get("LABEL_PARALLEL", "6"))


def parse_blocks(text):
    blocks, current, file, section = [], None, None, None
    for line in text.splitlines():
        if line.startswith("=== EDIT") or line.startswith("=== TURN"):
            current = {"kind": "edit" if "EDIT" in line else "turn", "note": "", "files": []}
        elif line == "=== END":
            blocks.append(current)
            current = None
        elif current is None:
            continue
        elif line.startswith("note:") and not current["files"]:
            current["note"] = line[5:].strip()
        elif line.startswith("--- file: "):
            file = {"path": line[10:].strip(), "before": [], "after": []}
            current["files"].append(file)
        elif line in ("--- before", "--- after"):
            section = line[4:]
        elif file is not None and section is not None:
            file[section].append(line)
    return blocks


def unified(path, before, after):
    diff = difflib.unified_diff(before, after, f"a/{path}", f"b/{path}", n=3, lineterm="")
    return "\n".join(diff)


def added_lines(before, after):
    matcher = difflib.SequenceMatcher(a=before, b=after, autojunk=False)
    added = set()
    for tag, _, _, j1, j2 in matcher.get_opcodes():
        if tag in ("replace", "insert"):
            added.update(range(j1 + 1, j2 + 1))
    return added


def run_gate(block):
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        git = lambda *args: subprocess.run(["git", "-C", tmp, *args], check=True, capture_output=True)
        git("init", "-q")
        for f in block["files"]:
            target = root / f["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            if f["before"]:
                target.write_text("\n".join(f["before"]) + "\n")
        git("add", "-A")
        git("-c", "user.name=label", "-c", "user.email=label@local", "commit", "-q", "--allow-empty", "-m", "before")
        for f in block["files"]:
            (root / f["path"]).write_text("\n".join(f["after"]) + "\n")
        git("add", "-A")
        out = subprocess.run(
            [str(GATE), "--diff", "staged", "--json", "--no-scalafmt", "--project-root", tmp],
            capture_output=True, text=True,
        )
        if not out.stdout.strip().startswith("{"):
            raise RuntimeError(f"gate failed on {[f['path'] for f in block['files']]}: {out.stderr[-500:]}")
        findings = json.loads(out.stdout)["findings"]
        for finding in findings:
            finding["file"] = str(Path(finding["file"]).relative_to(tmp)) if finding["file"].startswith(tmp) else finding["file"]
        return findings


def label(block):
    findings = run_gate(block)
    added = {f["path"]: added_lines(f["before"], f["after"]) for f in block["files"]}
    fired = sorted({
        f["rule"] for f in findings
        if f["rule"] in GROUP_OF and f["file"] in added
        and (f["rule"] in AGGREGATED or f["line"] in added[f["file"]])
    })
    groups = {g["id"]: int(any(GROUP_OF[r] == g["id"] for r in fired)) for g in GROUPS}
    files = [f["path"] for f in block["files"]]
    diff = "\n".join(unified(f["path"], f["before"], f["after"]) for f in block["files"])
    state = {"file": files[0], "diff": diff} if block["kind"] == "edit" else {"files": files, "diff": diff}
    return {"kind": block["kind"], "state": state, "labels": groups, "fired": fired, "note": block["note"]}


def label_all(blocks):
    with ThreadPoolExecutor(PARALLEL) as pool:
        return list(pool.map(label, blocks))


def fixture_blocks():
    """Each fixture as a new file under a realistic path: bad.scala must fire its rule's group, good.scala must not."""
    cases = []
    for rule_dir in sorted(FIXTURES.iterdir()):
        rule = rule_dir.name
        if rule not in GROUP_OF:
            continue
        frontend = GROUP_OF[rule] == "mech-frontend"
        base = "app/src/main/scala/com/acme/ui" if frontend else "server/src/main/scala/com/acme/core"
        for name, expected in (("bad.scala", 1), ("good.scala", 0)):
            source = rule_dir / name
            if source.exists():
                block = {"kind": "edit", "note": f"{rule}/{name}",
                         "files": [{"path": f"{base}/{rule.split('.')[-1].title().replace('-', '')}.scala",
                                    "before": [], "after": source.read_text().splitlines()}]}
                cases.append((rule, name, expected, block))
    return cases


def check_fixtures():
    cases = fixture_blocks()
    results = label_all([block for _, _, _, block in cases])
    failures = []
    for (rule, name, expected, _), result in zip(cases, results):
        got = result["labels"][GROUP_OF[rule]]
        if got != expected:
            failures.append(f"{rule}/{name}: expected {GROUP_OF[rule]}={expected}, got {got} (fired {result['fired']})")
    print(f"{len(cases)} fixture cases, {len(failures)} mismatches")
    for failure in failures:
        print("  " + failure)
    return 1 if failures else 0


def main():
    if sys.argv[1:] == ["--fixtures"]:
        sys.exit(check_fixtures())
    pattern, out = sys.argv[1], Path(sys.argv[2])
    blocks = [b for f in sorted(glob.glob(pattern)) for b in parse_blocks(Path(f).read_text())]
    records = label_all(blocks)
    out.write_text("".join(json.dumps(r) + "\n" for r in records))
    positives = {g["id"]: sum(r["labels"][g["id"]] for r in records) for g in GROUPS}
    print(f"{len(records)} examples -> {out}; positives per group: {positives}")


if __name__ == "__main__":
    main()
