#!/usr/bin/env bash
# PreToolUse gate: no PR opens until /ck has reviewed the branch up to HEAD.
#
# /ck writes the sha it reviewed up to into <git-dir>/ck-reviewed (per worktree). This hook blocks
# `gh pr create` and `gh api …/pulls` POSTs unless that stamp is HEAD, and tells Claude what to
# review instead: the whole branch when there is no stamp, only <stamp>..HEAD when there is one.
# Exit 2 blocks the call and hands stderr back to Claude.
set -uo pipefail

input=$(cat)
cmd=$(jq -r '.tool_input.command // empty' <<<"$input")
cwd=$(jq -r '.cwd // empty' <<<"$input")

# Only `gh` in command position counts: heredoc bodies and quoted strings are dropped first, so a commit
# message or PR body that mentions `gh pr create` does not trip the gate.
code_only() {
  perl -0777 -pe 's/([\x27"])([^\s\x27"]*)\1/$2/g; s/<<-?\s*([\x27"]?)(\w+)\1[^\n]*\n.*?^\s*\2\s*$//gms; s/\x27[^\x27]*\x27/\x27\x27/gs; s/"(?:\\.|[^"\\])*"/""/gs' <<<"$1"
}
at_cmd='(^|[;&|(]|\$\()[[:space:]]*'

opens_pr() {
  grep -Eq "${at_cmd}gh[[:space:]]+pr[[:space:]]+create\b" <<<"$1" && return 0
  # `gh api repos/o/r/pulls` (no number after it) creating a PR: explicit POST, or fields, which make gh POST.
  grep -Eq "${at_cmd}gh[[:space:]]+api\b.*repos/[^[:space:]/]+/[^[:space:]/]+/pulls([[:space:]]|$)" <<<"$1" &&
    grep -Eq '(-X|--method)[[:space:]=]*POST|[[:space:]](-f|-F|--field|--raw-field|--input)[[:space:]=]' <<<"$1"
}

opens_pr "$(code_only "$cmd")" || exit 0

block() { printf 'ck-gate: %s\n' "$1" >&2; exit 2; }

# The repo is the last `cd <dir>` before the gh call, else the shell's cwd.
dir=$cwd
target=$(grep -Eo '(^|[;&|[:space:]])cd[[:space:]]+[^;&|]+' <<<"${cmd%%gh *}" | tail -n1 |
  sed -E 's/^[;&|[:space:]]*cd[[:space:]]+//; s/[[:space:]]+$//; s/^["'"'"']//; s/["'"'"']$//')
if [[ -n $target ]]; then
  target=${target/#\~/$HOME}
  [[ $target = /* ]] && dir=$target || dir=$cwd/$target
fi

gitdir=$(git -C "$dir" rev-parse --absolute-git-dir 2>/dev/null) ||
  block "cannot tell which repository this PR is for (resolved '$dir', not a git checkout). cd into the repo first."
head=$(git -C "$dir" rev-parse HEAD)
stamp_file=$gitdir/ck-reviewed

[[ -s $stamp_file ]] ||
  block "no /ck review recorded for this branch ($dir). Run /ck over the whole branch, apply its fixes, then retry."

stamp=$(tr -d '[:space:]' <"$stamp_file")
[[ $stamp == "$head" ]] && exit 0

if git -C "$dir" merge-base --is-ancestor "$stamp" HEAD 2>/dev/null; then
  block "/ck reviewed up to ${stamp:0:12}, but HEAD is ${head:0:12}. Run /ck ${stamp}..HEAD to review only the newer commits, then retry."
fi

block "/ck stamp ${stamp:0:12} is not an ancestor of HEAD (rebase or amend?). If the rebase changed no content — check with \`git range-diff\` against the reviewed commits — write HEAD to $stamp_file and retry; otherwise run /ck over the whole branch."
