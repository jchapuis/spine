---
name: refactor-completeness
description: Refactor & pre-PR completeness audit. Use after moving/renaming/removing code, after creating new components, AND before opening any PR — checks for accidental reverts from a stale branch base (diff-audit), schema/config parity (numeric bounds, validators, configs, tests), missing test coverage for new components, stale README/comments/docstrings, necessity of every addition (unused flags, temp artifacts, dead exports), and file-per-class convention.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
---

# Refactor-Completeness — Pre-PR Audit Checklist

## Quick start
- **Before opening any PR**, rebase on the base branch if yours is more than a few commits behind, then run a **diff-audit**: skim every file in the diff and ask "did I deliberately touch this?".
- When you change a numeric bound, constant, or schema, check **sibling files** (codec validators, config files, test assertions, docs) for parity.
- After numeric tuning commits, **grep README/docs for the old number** — defaults and ranges quoted in prose drift silently.
- **PR description still matches HEAD?** After any follow-up commit, re-read the PR body; squashed/follow-up changes invalidate claims like "240-480 candidates" or "uses prefilter X".
- **Plumbed-but-not-wired check**: when you thread a new parameter through a function, verify a caller actually opts in. A new parameter defaulting to legacy behavior with no opt-in is a dormant feature.
- New component → new test (at least smoke-level) in the same PR.
- Stale prose: when removing a step from a pipeline, grep README/comments/PR description for references to it.
- **Necessity audit**: every new flag, tool, export, or file has a consumer today — temp artifacts removed, settled decisions delete the losing path (section E).
- One file per top-level class (Scala convention) — split multi-class files; filename matches the principal type.

## Why
The cautionary tale: a feature PR with ~50 review comments where roughly a seventh were silent reverts of recently-merged work, because the branch was based on an older master. Security hardening, numeric bounds, and component wiring all silently regressed. A pre-PR diff-audit catches every one of these.

Other recurring miss patterns that this checklist catches:
- README/docstrings still describing the *old* behavior after a refactor.
- Numeric bound changed in code but a config file still has the old value.
- New presenter or service introduced without a test.
- Only the happy path exercised — `train` mode references a field never set in `__init__`; AMP path computes loss against the wrong tensor shape.
- New parameter threaded through a function but no caller actually opts in — feature plumbed but dormant in production.
- Numeric tuning commits leave the README quoting the previous range.
- PR description claims "uses X prefilter" or "240-480 candidates" after later commits invalidated both.

## The audit (run before opening every PR)

### A. Branch hygiene — catch silent reverts

1. **Rebase if the branch is stale**. Resolve `<base>` as in `compliance-gate` (`origin/master` → `origin/main` → `master` → `main`). If `git log <base>..HEAD` has many commits *and* `git log HEAD..<base>` has any (the branch is behind), rebase or merge the base in first. The diff against the base should reflect *your* changes, not "your changes minus what landed on the base since."

2. **Diff-audit** — list every changed file:
   ```sh
   git diff <base>...HEAD --stat
   ```
   For each file, ask: did I deliberately modify this? If no, open it and check for reverts. Tell-tales:
   - **Infra/security configs** (load-balancer/deployment definitions, `*.conf`) being touched in a feature PR — almost always wrong.
   - **Constants reverting** to older values (limits, tolerance factors, bucket counts).
   - **Service/algebra wiring vanishing** ("how come `OrderAlg` disappears?").
   - **Test assertions reverting** to looser bounds.

3. **Cross-check against recent merged PRs**. `gh pr list --state merged --limit 20 --json number,title,mergedAt`: if any of those touched files in your diff, double-check you're not undoing them.

### B. Schema / config parity

When you change a constant, bound, or schema, search for **sibling places**:

| Change | Sibling places to check |
|---|---|
| Numeric bound in code (`MaxRetries = 5`) | codec validators, refined-type constraints, config files (`*.conf`), test assertions, documentation |
| Schema in DB / Skunk codec | repository read/write paths, integration tests, JSON serialisation |
| API request/response field | Tapir endpoint, STTP client, OpenAPI snapshot, frontend types |
| Feature flag | all branches that consume it; default in config; tests for both states |

A quick `rg <constantName>` or `rg <oldValue>` across the repo will surface mismatches.

### C. Refactor consistency

- **Prose** — when you remove a step from a pipeline, grep README, in-code comments, and the PR description for references to that step.
- **Numeric defaults / ranges in prose** — after tuning a constant, grep README/docs for the old number or range. Prose-quoted values (`240-480 candidates`, `tolerance 1.5`) drift silently when only the code is updated.
- **PR description vs HEAD** — after every follow-up commit on an open PR, re-read the PR body. Claims like "uses prefilter X" or "candidate count = N" become wrong as later commits land. Update before requesting review.
- **Type signatures and docstrings** — when you change a return type or arity, walk every docstring/comment that describes it.
- **Signature-change test audit** — when changing a function signature so it now covers a broader case (`headOption → list`, single value → collection, sync → async, one record → batch), audit existing tests for the cases the new signature now covers. A test that only exercised the old narrow case will still pass without exercising the new behavior at all — and the regression is invisible until production data uses the wider shape.
- **Related code paths** — if you only exercised one mode (eval, success path, happy flow), the other paths likely break: a non-default branch may reference a field never initialized, a non-AMP/AMP code path may compute against the wrong tensor shape.
- **Plumbed-but-not-wired** — when you add an optional parameter to a function, confirm at least one caller passes the non-default value. A new param defaulting to legacy behavior with no opt-in is a feature shipped dormant — the deployed binary still runs the old path.
- **Test coverage of moved logic** — when relocating a method, restate the responsibility split in the PR description and confirm tests follow the move.
- **Naming for new semantics** — a field/output description like `"status code"` for what now carries a `"duration in seconds"` value is a silent bug.

### D. New components

- **New presenter / service / non-trivial pure function → smoke test in the same PR.**
- **One file per top-level class** (Scala convention) — filename matches the principal type; split multi-class files. Cross-link to `scala`.

### E. Necessity audit — everything in the diff earns its place

For each *addition* in the diff (flag, parameter, tool, script, export, file), ask "who consumes this, today?":

- **New boolean flag / toggle** — answer "when would we ever disable this?" concretely. No real answer → the flag is speculative; delete it and hardcode the behavior.
- **New tool / script / CI step** — wired into CI or an actual workflow, or dropped. A helper script nobody invokes is dead on arrival.
- **Temp / experiment artifacts** — exploration scripts, findings docs, scratch fixtures, one-off migrations that never ran: removed before the PR.
- **Exports / fields / config entries** — read anywhere? A field that is never `.set` or never read, an export with no downstream consumer, ships as dead weight.
- **Settled decisions delete the alternative** — when a choice lands (a model, a provider, an algorithm), remove the losing path's plumbing in the same PR ("we settled — drop this complexity now"). Keeping it "in case" is the dormant-feature antipattern with extra branches.
- **Abstraction with one implementation and no second in sight** — inline it (cross-link `reuse-first` step 0).

## The diff-audit one-liner
```sh
git diff <base>...HEAD --stat | sort -k3 -n -r | head -30
```
Bias your attention toward the largest changes; then go down the list. For any file you didn't deliberately edit, open it and verify each hunk.

## Decision rule
A PR is ready to open when:
1. `git diff <base>...HEAD --stat` lists only files you deliberately modified.
2. Every numeric/schema change has been searched for siblings (configs, codecs, tests, README/docs prose).
3. Every new top-level component has a test in the same PR.
4. README / comments / PR description match the code at HEAD (re-read the PR body after every follow-up commit).
5. Every code path you didn't exercise locally has at least been read once.
6. Every new optional parameter has at least one caller opting in (no plumbed-but-dormant features).
7. Every addition passes the necessity audit — a consumer exists today; no temp artifacts, dead flags, or superseded alternatives remain.

## Red flags — stop and audit
| Excuse | Reality |
|---|---|
| "The rename was mechanical, nothing else can be stale" | README prose, docstrings, and PR bodies don't compile — mechanical renames are exactly what leaves them stale. |
| "Tests pass, so the refactor is complete" | Passing tests prove only the cases the old shape exercised; a widened signature needs new cases. |
| "Config parity can wait for a follow-up" | The mismatched bound ships to production first; siblings are one `rg <oldValue>` away. |
| "My branch is only slightly behind the base" | The 50-comment cautionary tale above started as "slightly behind". The diff-audit is unconditional; rebase as soon as the diff shows anything you didn't write. |
| "I didn't touch that file" | If it's in the diff anyway, your stale base did — that's precisely the silent-revert tell-tale. |
| "The PR description was right when I opened it" | Every follow-up commit can invalidate it; re-read before requesting review. |

## Cross-references
- `scala` — file-per-class convention, naming consistency.
- `factor-out` — extraction opportunities surface as you read your own diff.
- `fail-loud` — an unintended fallback or sentinel is itself something to audit out.
