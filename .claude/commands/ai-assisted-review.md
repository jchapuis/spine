Assist with reviewing a pull request: gather findings, dedup against what's already
been said, and walk the human through each finding to post (or skip) it as an inline
PR comment. The argument is the **branch name** of the PR to review.

Run from inside the project repository that owns the branch (the working dir must be
that git repo, not the spine root). All `gh` calls operate on that repo's remote.

#$ARGUMENTS

## 0. Resolve the PR

- `gh pr view "$ARGUMENTS" --json number,title,url,baseRefName,headRefName,headRefOid,state`
- If no PR is found for the branch, stop and tell the user (offer to list open PRs).
- Capture: PR number, base branch, **head SHA** (`headRefOid` — needed as `commit_id`
  when posting inline comments), and `owner/repo` (`gh repo view --json nameWithOwner -q .nameWithOwner`).
- Make sure the branch is checked out locally so the diff is available to the review
  steps: `git fetch origin` then `git checkout "$ARGUMENTS"` (skip the checkout if already
  on it).
- **Always review against the latest remote base** — i.e. the GitHub diff, not a stale
  local base. After fetching, the diff under review is `origin/{baseRefName}...HEAD`
  (`git diff origin/{baseRefName}...HEAD`, where `{baseRefName}` is the PR's base from the
  `gh pr view` output, typically `master`). Use this explicit base range — never an
  implicit local `master` that may lag behind the remote — when handing the diff to the
  review subagents in step 2.

## 1. Scan existing comments (for dedup only)

Collect everything already said on the PR so findings that repeat it can be dropped:

- Inline review comments: `gh api repos/{owner}/{repo}/pulls/{n}/comments --paginate`
  → keep `{path, line, body}`.
- PR-level comments: `gh api repos/{owner}/{repo}/issues/{n}/comments --paginate`
  → keep `{body}`.
- Review summaries: `gh api repos/{owner}/{repo}/pulls/{n}/reviews --paginate`
  → keep `{body}`.

Hold this as the "already raised" set. It is used only for filtering in step 3.

## 2. Gather findings (parallel subagents)

Launch both gathering engines **concurrently as subagents** — in a single message with
two Agent calls — so they run in parallel and keep their verbose output out of the main
context. Each agent returns a structured findings list as its final message; a finding is
`{path, line, severity, problem, suggested_comment}`.

Both agents review the **`origin/{baseRefName}...HEAD`** diff resolved in step 0 (the
up-to-date GitHub diff against the freshly fetched remote base) — pass this base range to
each agent explicitly so neither falls back to a stale local base.

- **Agent A — `/code-review`:** run it in **collect-only** mode (do NOT pass `--comment`;
  you drive the posting yourself). Tell the agent to return its findings as the structured
  list above, each mapped to a `path:line`, and nothing else.
- **Agent B — `/compliance-gate`:** run the compliance-gate skill against the PR diff.
  Tell the agent to return each violation as the same structured shape — `path:line`, a
  short problem statement, and a suggested comment — and nothing else.

When both agents return, merge their lists into one, de-duplicating findings that point at
the same `path:line` + concern across the two engines (keep the clearer wording).

**Verify each surviving finding yourself before it reaches the user.** The agents can be
wrong; open the file, confirm the line and the claim actually hold, and check for local
precedent (how the surrounding code and the rest of this repo already handle it) before
flagging. Drop anything that doesn't survive your own read — don't pass through a finding
on the agent's word alone.

## 3. Filter against existing comments (silently)

Drop any finding that overlaps the "already raised" set — same file and nearby line, or
clearly the same concern stated in a PR-level/review comment. Do this silently: do not
ask about dropped findings. Just remember the count.

Report once before the loop. Lead with the count, then give a one-line recap of what each
engine found (including "clean") so the user sees the shape of the review, e.g.:
> **7 findings, 3 already raised → 4 to review.**
> `/code-review`: 2 correctness (verified), 1 dropped on my own read. `/compliance-gate`:
> mechanical clean, 2 low-severity `factor-out` notes.

If nothing remains, say so — with the same per-engine recap — and stop.

## 3b. Humanize the recommended wording (kind tone)

Before the loop, turn each finding's `suggested_comment` into the wording that will be
shown and posted:

- If the **humanizer** skill is available, run each `suggested_comment` through it so the
  text reads naturally rather than machine-generated.
- Make the tone **kind**: collaborative and constructive, never terse or accusatory.
  Frame as a suggestion or question to a peer (e.g. "Could we…", "What do you think
  about…", "Small thing —…"), acknowledge intent, and keep it concise.

Use this humanized, kind text as the recommended wording in the loop below.

## 4. Per-finding loop

For **each** remaining finding, ask the user with the AskUserQuestion tool — one finding
per question. The question body states `path:line`, the severity, and the problem. Offer:

1. **Post this comment** *(recommended)* — put the full humanized, kind comment text in the
   option **description** so the user sees exactly what will be posted.
2. **Edit wording** — the user wants to change the comment before it goes out.
3. **Skip** — do not post; move on.
4. **Stop review** — end the loop now.

> **Treat AskUserQuestion as a selection-only tool. Do NOT rely on its note field.** Notes
> attached to a selection are not reliably surfaced back to you — you will usually receive
> only the chosen label. So never read a comment body, or any wording instruction, out of a
> note; if one does happen to reach you, treat it as a bonus, not the channel. Every wording
> change flows through a normal message exchange, described below.

Handle the selection:

- **Post this comment** → post the recommended text **verbatim and immediately** (see
  below). No re-confirm needed — the user already saw the exact text in the option.
- **Edit wording** → in your **next turn**, ask the user in plain text what to change (or to
  paste their own wording). Draft the revised comment, show the **final exact text**, and
  wait for an explicit go-ahead before posting. Loop until they're happy.
- **Skip** → move to the next finding.
- **Stop review** → end the loop, go to the summary.

**Re-confirm before posting whenever you have revised the text at all** — show the final
wording and get an explicit yes. Only an untouched *Post this comment* selection posts
without a re-confirm.

When posting (an accepted recommendation, or edited text the user confirmed), post the
comment **immediately** as an inline review comment via the REST API (never `gh pr edit`):

```bash
gh api repos/{owner}/{repo}/pulls/{n}/comments \
  -f body="<comment text>" \
  -f commit_id="<head SHA>" \
  -f path="<path>" \
  -F line=<line> \
  -f side=RIGHT
```

- For a multi-line range, add `-F start_line=<n> -f start_side=RIGHT`.
- If the API rejects the line (e.g. the line is not part of the diff), fall back to a
  PR-level comment (`.../issues/{n}/comments`) that quotes `path:line`, and tell the user.

## 5. Summary

When the loop ends (all findings handled or the user chose Stop), report:
> Posted N inline comments, skipped M, filtered D already-raised. PR: <url>
