---
description: Run /code-review and /compliance-gate in parallel sonnet agents
---

Run this **before committing or pushing**, not after. Findings that arrive once the branch is
public cost a rewrite, a force-push and a second CI round; the same findings an hour earlier cost
an edit.

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
> Invoke the `compliance-gate` skill and follow it exactly, scoped to the same target. When the target includes uncommitted work, run the mechanical layer with `--diff branch` **and** `--diff workdir`: `branch` sees committed work only, and only `workdir` lists untracked files. Return every
> violation with file:line, the skill it breaks, and the fix. Name the skills you checked that
> passed clean. Run the skill's necessity pass (step 6b) over the whole target against its stated
> intent (PR body or commit messages) and report its findings in their own section. Run the jargon
> pass (step 6c) over the same target and report it in a section of its own. If the mechanical layer has already been run, say so and spend your effort on the
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
finding to one line, and separate confirmed defects from speculative concerns. Do not fix anything
unless asked.

Two things to weigh rather than accept:

- A finding that reads as an *accidental* revert or omission may be a deliberate decision the
  reviewer could not see. Check the intent before acting — but if a reviewer misread it, the commit
  message that let them is worth fixing too.
- Findings that indict a design decision are the valuable ones. Surface them as decisions for the
  user rather than quietly implementing around them.
