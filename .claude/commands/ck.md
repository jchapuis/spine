---
description: Run /code-review and /compliance-gate in parallel sonnet agents
---

Run this **before the work goes public**: before the PR opens, and before follow-up commits are
pushed to an open PR. Findings that arrive once the branch is public cost a rewrite, a force-push
and a second CI round; the same findings an hour earlier cost an edit. A hook enforces the first
case (see "Stamp the review").

Resolve the target first, and state it to both agents explicitly:

- With `$ARGUMENTS`, use that.
- Otherwise review the **whole branch** — `git diff origin/master...HEAD` plus any uncommitted work
  — not just the tip commit. A file added in the first commit and never touched again is invisible
  to a tip-only or workdir-only range, and that is exactly where violations hide.

Launch both checks as subagents in a **single message** so they run concurrently.
Both use **`subagent_type: review-worker`** — that agent type has no `Agent` tool, so neither pass
can fan out, and no `Edit`/`Write`, so neither can quietly start fixing. Do not substitute
`general-purpose`; the restriction is the whole point.

> Removing the `Agent` tool is a backstop, not the whole cure: the skill also reaches `/code-review`
> as a command-backed task, which a tool allowlist does not gate. `Explore` — advertised as having
> no `Agent` tool — still produced a `/code-review` child when tried. **The effort level is the
> load-bearing lever**; treat `review-worker` as defence in depth, and check `ListAgents` once after
> launching rather than assuming the restriction held.

**State the effort level explicitly.** `code-review` with no level reuses the level typed last in
the session — that is how a `max` from an unrelated earlier run turned a 13-file diff into a
four-agent fleet, one of which ran 46 minutes and 4.6 MB and returned nothing. Pass `medium` unless
the user asked for more, and never leave it unstated.

**If the target lives in a linked worktree, say so in both prompts, with the absolute path.** A
subagent's shell cwd can reset to the main checkout, which is a different branch carrying unrelated
work; a review of that is worthless and reads as plausible. Tell each agent to use `git -C <path>`
and to stop and re-anchor if what it is reading does not match the stated target.

Agent 1 — code review:
> Invoke the `code-review` skill with arguments `$ARGUMENTS` (if empty, review the resolved target
> above, at effort `medium`) and follow it exactly. Return the findings verbatim: file:line,
> severity, and the defect with a concrete failure scenario. Also report what you checked and found
> **clean** — a cleared hypothesis is worth reporting, because it stops the next reviewer
> re-deriving it.

Agent 2 — compliance gate:
> Invoke the `compliance-gate` skill and follow it exactly, scoped to the same target. When the target includes uncommitted work, run the mechanical layer with `--diff branch` **and** `--diff workdir`: `branch` sees committed work only, and only `workdir` lists untracked files. Pass `--base` as the target range's base (`origin/master` for a whole branch). Run the `refactor-completeness` audit over the target too: diff-audit, config parity, missing tests and stale docs. `/go` relies on this pass for it. Return every
> violation with file:line, the skill it breaks, and the fix. Name the skills you checked that
> passed clean. Run the skill's necessity pass (step 6b) over the whole target against its stated
> intent (PR body or commit messages) and report its findings in their own section. Run the jargon
> pass (step 6c) over the same target and report it in a section of its own. Run the simplicity
> pass (step 6d) over the same target and report it in a section of its own, worth-it findings
> before taste, and decisions that would reverse a recorded choice apart. If the mechanical layer has already been run, say so and spend your effort on the
> LLM-judgment layer instead of repeating it.

Tell each agent what the other is covering so they partition rather than overlap.

**Exactly two agents. Never more.** Do not add review angles of your own. Telling an agent in prose
not to fan out does not hold — it has been tried and the skill's own instructions won: both skills
split into per-angle agents that each re-read the whole diff, and a run over a large branch has
exhausted the session limit and returned nothing at all, because the parent cannot report while a
child is still live. `review-worker` is the fix, because it removes the tool rather than asking.
Two agents that finish beat ten that do not.

If a run goes quiet, check for the signature before waiting it out: `ListAgents` shows the agent you
launched as `completed` while a child of it is still `running`. Stop the child with `TaskStop`, then
message the parent for what its finished angles established.

If the target is too large for two agents to cover, narrow the target — review the unreviewed work
rather than re-reviewing commits that already carry review history — instead of widening the fleet.

When both return, merge into one report: dedupe overlapping findings, order by severity, keep each
finding to one line, and separate confirmed defects from speculative concerns.

## Triage: fix by default, ask only what is truly open

The user accepts nearly every suggestion, so asking about each one only slows the PR down. Sort
every finding into one of three buckets:

- **Fix.** This is the default. It applies to any confirmed finding whose fix you can name without
  choosing between options: bugs, skill violations, mechanical-layer hits, duplication, stale docs,
  jargon, and simplicity findings marked worth-it. Verify the finding against the code first, then
  fix it yourself (the review workers cannot edit).
- **Drop.** A speculative finding you cannot confirm from the code, or a taste-only simplicity note.
  List it in the report as dropped, with the reason, and do nothing more.
- **Decision.** Use this only when one of the following holds:
  - The fix would reverse a recorded choice (README, ADR, task file, commit message, memory).
  - There are two or more reasonable fixes with real trade-offs between them.
  - The fix needs context that isn't in the repo, such as product intent, an outside constraint,
    or what a partner expects.
  - The fix goes well beyond the diff: a public API, a schema, or another project.
  - It is a necessity (6b) finding that would delete work the stated intent asked for.

  A finding that reads as an *accidental* revert or omission may have been deliberate. Check the
  intent first. If it was deliberate, the finding is a decision, or a drop. If the reviewer
  misread it, fix the commit message or comment that misled them.

Do the fixes, run what the touched code needs (compile, the tests nearby, and the compliance-gate
mechanical layer on the touched files), then **commit them as their own commit**
(`fix: address /ck findings`). Leave decision items alone, and don't work around them quietly.

## Stamp the review

The stamp is a file, `<git-dir>/ck-reviewed`, that holds the last sha `/ck` reviewed up to. Each
worktree has its own. The PR gate (`.claude/hooks/ck-gate.sh`, a `PreToolUse` hook in the user
settings, so it applies to sessions in every project) lets a PR open only when the stamp is HEAD.
Otherwise it asks for `/ck <stamp>..HEAD`, which reviews only the newer commits.

Stamp **only when the stamp would be true**:
- The target ran from the previous stamp, or from the merge-base when there was none, up to HEAD.
  If it was a narrower target (some paths, or a range that starts later), don't stamp.
- After the fix commit, the tracked tree is clean (`git -C <path> diff --quiet HEAD`), apart from
  changes you know are unrelated and will not be pushed. Work that
  isn't committed isn't in HEAD, so a stamp can't vouch for it.

Then write it, using the target's checkout (its worktree, if it has one):

```bash
git -C <path> rev-parse HEAD > "$(git -C <path> rev-parse --absolute-git-dir)/ck-reviewed"
```

Stamp **after** the fix commit. Those fixes come straight from the findings, so they count as
reviewed. The gate's own tests are in `.claude/hooks/ck-gate.test.sh`; run them after changing it.

## Send decisions to the Plannotator Inbox

Decisions never hold up the PR. Ask them in the inbox and keep going.

**Always pass `project_path`** set to the repo's main checkout:
`dirname "$(git -C <path> rev-parse --path-format=absolute --git-common-dir)"`, for example
`/Users/jchapuis/Seafile/spine/projects/gritmaps`. Without it, the inbox files the message under the
folder the session started in, which is usually the spine root. The message then lands in a separate
spine project instead of beside the PR's guided review. A worktree path has the same problem.

One thread per PR, `thread: "PR #<number>"`. The guided review and every decision about that PR
go there, so the person reads them together.

- **No PR yet (the usual case, since the gate runs `/ck` just before `gh pr create`).** Write the
  decisions to `<git-dir>/ck-decisions.md`, **overwriting** it so it only ever holds this run's
  open decisions. Once the PR is open, send them **with** the guided review:
  `get_guide_brief`, then a single `submit_guide` call. Its `body` carries the decision questions
  and its `patch` is `gh pr diff <number>`. That is one message, not a guide followed by a separate
  question. Then delete the file. The global CLAUDE.md's post-PR guide step does the same, so the
  file is used up whichever session opens the PR.
- **Large PR (20+ files or 600+ changed lines): add a PR Lens diagram to the guide.** After the
  last commit and before `gh pr create`, follow the `pr-lens` skill steps 1 to 4: write the
  graph, validate it, and render it with the light theme. Skip `canvas push` and `--attach`, because the diagram
  stays on this machine. In the `submit_guide` call, show the top architecture view in `body` as
  `![<what it shows>](<absolute path to the svg>)`, and list the SVG under `attachments` too.
  Add a data-flow view only when the change has a sequence worth following. The gate blocks a
  large PR until a render newer than HEAD exists.
- **PR already open (an incremental `/ck` after new commits).** Skip the file. Call `read_thread`
  on that thread first, and don't re-ask a decision that is already open there. Send the new ones
  with `send_message` and `reply_to`, and use `supersedes` to list earlier questions that this run
  settled or made moot, so stale cards stop sitting at the top of the person's list.
- Pass `idempotency_key: "ck-<reviewed sha>"` so a retry of the same send never posts twice.
- Send with this session's own `plannotator_inbox` tool, even when the patch is long. A message sent
  some other way, such as through a separate `plannotator inbox mcp` process, routes the reply to that
  process instead of to this session.

Each decision is a `:::question` block, at most about 8 per run:
- The first line is the question, in product terms.
- Then one to three plain sentences of context: what the finding is (with `file:line`), why it is
  open (which reason above applies), and why you recommend the option you do.
- Then the choices, each with its consequence after a ` — `, and a `Recommended:` line.
- Add `Holds up: merging #<number>` only when merging should wait for the answer. Never add
  `Stopped:`, because the PR goes ahead without the answer.

**Do not wait for the answer.** No `wait_for_reply` and no polling: end the turn, or keep working.
The reply comes back into the session by itself, headed `Plannotator Inbox: …`, with the choices
under "Answers to your questions". Apply them in a follow-up commit, then run `/ck <stamp>..HEAD` before
pushing it. The gate only guards PR creation, so this step is on you. Then use `send_message` with `reply_to` to say in one line what changed. Once
nothing in the thread is left for the person (the PR is merged and every question is answered),
call `resolve_message`.

If the person is actively talking with you in this session, ask in the chat instead. The inbox is
for when they are not watching.

## Report

End with a short summary of the fixes made (one line each), the decisions sent to the inbox, and
the findings dropped with their reasons.
