#!/usr/bin/env bash
# Runs ck-gate.sh against a throwaway repo: commands that open a PR must block without a fresh stamp,
# everything else must pass. Usage: .claude/hooks/ck-gate.test.sh
set -uo pipefail

gate=$(cd "$(dirname "$0")" && pwd)/ck-gate.sh
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
repo=$tmp/repo
git init -q "$repo" && git -C "$repo" commit -q --allow-empty -m one && git -C "$repo" commit -q --allow-empty -m two
git -C "$repo" update-ref refs/remotes/origin/master HEAD
stamp=$repo/.git/ck-reviewed
failures=0

expect() { # expect <exit code> <command> [cwd]
  local got err
  err=$(jq -n --arg c "$2" --arg d "${3:-$repo}" '{tool_input: {command: $c}, cwd: $d}' | "$gate" 2>&1)
  got=$?
  [[ $got == "$1" ]] || { printf 'FAIL want %s got %s: %s\n  %s\n' "$1" "$got" "$2" "$err"; failures=$((failures + 1)); }
}

# No stamp: everything that opens a PR blocks.
for c in 'gh pr create --fill' 'gh pr new' 'gh -R o/r pr create' 'gh --repo=o/r pr create' 'FOO=1 gh pr create' \
  'env gh pr create' 'command gh pr create' 'echo x | xargs gh pr create' 'bash -c "gh pr create"' \
  'if true; then gh pr create; fi' '{ gh pr create; }' '(gh pr create)' 'echo $(gh pr create --fill)' \
  'git push && gh pr create --title "x" --body "it'"'"'s done"' \
  'gh api repos/o/r/pulls -f title=x' 'gh api "repos/o/r/pulls" -f title=x' 'gh api repos/o/r/pulls --method=POST --input b.json' \
  'gh api graphql -f query="mutation { createPullRequest(input: {}) { pullRequest { url } } }"'; do
  expect 2 "$c"
done

# Commands that do not open a PR pass, even when their text mentions one.
for c in 'git status' 'gh pr view 12' 'gh pr list' 'gh api repos/o/r/pulls' 'gh api repos/o/r/pulls --method GET -f state=open' \
  'gh api repos/o/r/pulls/12 -X PATCH -f body=x' 'git commit -m "run gh pr create later"' "echo 'gh pr create'" \
  $'git commit -F - <<\'EOF\'\nblocks `gh pr create`\ngh pr create at line start\nEOF' 'echo high; ls ugh'; do
  expect 0 "$c"
done

# cd is followed in order, from any cwd.
expect 2 "cd $repo && gh pr create" /
expect 2 "cd $tmp && cd repo && gh pr create" /
expect 2 "(cd $repo && gh pr create)" /
expect 2 "cd /nonexistent && gh pr create"

# Stamps: HEAD passes, an ancestor asks for an incremental review, anything else blocks.
git -C "$repo" rev-parse HEAD >"$stamp" && expect 0 'gh pr create'
git -C "$repo" rev-parse HEAD~1 >"$stamp" && expect 2 'gh pr create'
echo deadbeef >"$stamp" && expect 2 'gh pr create'

# A large PR also needs a PR Lens render newer than HEAD; a small one does not.
git -C "$repo" update-ref refs/remotes/origin/master HEAD
seq 1 700 >"$repo/big.txt" && git -C "$repo" add big.txt && git -C "$repo" commit -q -m big
git -C "$repo" rev-parse HEAD >"$stamp" && expect 2 'gh pr create'
mkdir -p "$repo/.pr-lens/overview" && touch "$repo/.pr-lens/overview/manifest.json" && expect 0 'gh pr create'
touch -d '2000-01-01' "$repo/.pr-lens/overview/manifest.json" && expect 2 'gh pr create'
CK_GATE_LARGE_LINES=10000 expect 0 'gh pr create'
# No origin/HEAD and no origin/master: origin/main is used, and with no base at all the gate says so.
git -C "$repo" update-ref -d refs/remotes/origin/master && expect 2 'gh pr create'
git -C "$repo" update-ref refs/remotes/origin/main HEAD~1 && touch "$repo/.pr-lens/overview/manifest.json" && expect 0 'gh pr create'

# Unreadable input blocks only what looks like a PR.
printf '{bad gh pr create' | "$gate" 2>/dev/null; [[ $? == 2 ]] || { echo 'FAIL bad input with a PR'; failures=$((failures + 1)); }
printf '{bad' | "$gate" 2>/dev/null; [[ $? == 0 ]] || { echo 'FAIL bad input without a PR'; failures=$((failures + 1)); }

((failures == 0)) && echo "ck-gate: all checks pass" || { echo "ck-gate: $failures failing"; exit 1; }
