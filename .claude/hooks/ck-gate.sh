#!/usr/bin/env bash
# PreToolUse gate: no PR opens until /ck has reviewed the branch up to HEAD, and a large PR also needs a PR Lens diagram.
#
# /ck writes the last sha it reviewed into <git-dir>/ck-reviewed (the stamp; per worktree). This hook
# blocks a Bash call that opens a PR unless the stamp is HEAD, and tells Claude what to review
# instead: the whole branch when there is no stamp, only <stamp>..HEAD when there is one.
# Exit 2 blocks the call and hands stderr back to Claude. Tests: ck-gate.test.sh beside this file.
set -uo pipefail

input=$(cat)

block() { printf 'ck-gate: %s\n' "$1" >&2; exit 2; }

# Without jq or perl, or with input we cannot read, the command cannot be checked: block only what
# looks like it opens a PR, so a broken machine does not silently lose the gate.
if ! command -v jq >/dev/null || ! command -v perl >/dev/null ||
  ! cmd=$(jq -er '.tool_input.command' <<<"$input" 2>/dev/null); then
  grep -Eq 'gh.*(pr[[:space:]]+(create|new)|/pulls|createPullRequest)' <<<"$input" &&
    block "cannot parse this command (jq or perl missing, or unreadable hook input), so cannot check it for /ck."
  exit 0
fi
cwd=$(jq -r '.cwd // empty' <<<"$input")

# Prints the directory a PR-opening command runs in, or nothing when the command opens no PR.
# Heredoc bodies and quoted strings are dropped first, so a commit message or PR body that mentions
# `gh pr create` does not count. The rest is split into simple commands; `cd`s are followed in order,
# and env assignments, wrappers (env, command, xargs…) and gh's global flags (-R) are skipped.
read -r -d '' find_pr_dir <<'PERL'
my ($cmd, $dir) = @ARGV;
my $home = $ENV{HOME};
if (($cmd =~ /\bgh\s+api\s+graphql\b/ && $cmd =~ /createPullRequest/) ||
    $cmd =~ /\b(?:ba|z)?sh\s+-c\b.*\bgh\b.*\bpr\s+(?:create|new)\b/s) { print $dir; exit }
$_ = $cmd;
s/(['"])([^\s'"]*)\1/$2/g;
s/<<-?\s*(['"]?)(\w+)\1[^\n]*\n.*?^\s*\2\s*$//gms;
s/'[^']*'/''/gs;
s/"(?:\\.|[^"\\])*"/""/gs;
for my $seg (split /\|\||&&|\$\(|[;|&\n(){}`]/) {
  my @w = split ' ', $seg;
  shift @w while @w && ($w[0] =~ /^\w+=/ ||
    $w[0] =~ /^(?:!|if|then|elif|else|while|until|do|env|command|exec|time|nohup|sudo|xargs)$/);
  next unless @w;
  if ($w[0] eq 'cd') {
    my $t = $w[1] // $home;
    $t =~ s/^~(?=\/|$)/$home/;
    $dir = $t =~ m{^/} ? $t : "$dir/$t";
    next;
  }
  next unless shift(@w) eq 'gh';
  while (@w && $w[0] =~ /^-/) { my $f = shift @w; shift @w if $f =~ /^(?:-R|--repo|--hostname)$/ }
  if (@w >= 2 && $w[0] eq 'pr' && $w[1] =~ /^(?:create|new)$/) { print $dir; exit }
  my $s = join ' ', @w;
  if (@w && $w[0] eq 'api' && $s !~ /(?:-X|--method)[\s=]*GET\b/ &&
      $s =~ m{\brepos/[^\s/]+/[^\s/]+/pulls(?:\s|$)} &&
      $s =~ /(?:-X|--method)[\s=]*POST\b|\s(?:-f|-F|--field|--raw-field|--input)(?:\s|=)/) { print $dir; exit }
}
PERL

dir=$(perl -e "$find_pr_dir" -- "$cmd" "$cwd")
[[ -n $dir ]] || exit 0

gitdir=$(git -C "$dir" rev-parse --absolute-git-dir 2>/dev/null) ||
  block "cannot tell which repository this PR is for (resolved '$dir', not a git checkout). cd into the repo first."
head=$(git -C "$dir" rev-parse HEAD)
stamp_file=$gitdir/ck-reviewed

# A large PR also needs a PR Lens diagram for its inbox guide, rendered after the last commit:
# a .pr-lens/*/manifest.json newer than HEAD. Large means CK_GATE_LARGE_FILES files or
# CK_GATE_LARGE_LINES changed lines against the default branch.
check_diagram() {
  local base mb files lines commit_time manifest main_checkout
  base=$(git -C "$dir" symbolic-ref -q --short refs/remotes/origin/HEAD) ||
    for base in origin/main origin/master ""; do git -C "$dir" rev-parse -q --verify "$base" >/dev/null && break; done
  [[ -n $base ]] && mb=$(git -C "$dir" merge-base "$base" HEAD 2>/dev/null) ||
    block "cannot tell this PR's base branch to size it (no origin/HEAD, origin/main or origin/master). Run \`git remote set-head origin --auto\`, then retry."
  read -r files lines < <(git -C "$dir" diff --numstat "$mb" HEAD | awk '{f++; l += $1 + $2} END {print f+0, l+0}')
  ((files >= ${CK_GATE_LARGE_FILES:-20} || lines >= ${CK_GATE_LARGE_LINES:-600})) || return 0
  # The render may sit in this checkout or, for a linked worktree, in the main one.
  commit_time=$(git -C "$dir" log -1 --format=%ct HEAD)
  main_checkout=$(dirname "$(git -C "$dir" rev-parse --path-format=absolute --git-common-dir)")
  for manifest in "$(git -C "$dir" rev-parse --show-toplevel)"/.pr-lens/*/manifest.json "$main_checkout"/.pr-lens/*/manifest.json; do
    [[ -f $manifest ]] && (($(perl -e 'print((stat shift)[9])' "$manifest") >= commit_time)) && return 0
  done
  block "large PR ($files files, $lines changed lines against $base). Render a PR Lens diagram of the diff for the inbox guide first, from the repo's top folder: pr-lens skill steps 1-4 (write, validate, render with the light theme; no canvas push, no --attach), then retry."
}

[[ -s $stamp_file ]] ||
  block "no /ck review recorded for this branch ($dir). Run /ck over the whole branch, apply its fixes, then retry."

stamp=$(tr -d '[:space:]' <"$stamp_file")
[[ $stamp == "$head" ]] && { check_diagram; exit 0; }

git -C "$dir" merge-base --is-ancestor "$stamp" HEAD 2>/dev/null &&
  block "/ck reviewed up to ${stamp:0:12}, but HEAD is ${head:0:12}. Run /ck ${stamp}..HEAD to review only the newer commits, then retry."

block "/ck reviewed ${stamp:0:12}, which is not an ancestor of HEAD (rebase or amend?). If the rebase changed no content — check with \`git range-diff\` against the reviewed commits — write HEAD to $stamp_file and retry; otherwise run /ck over the whole branch."
