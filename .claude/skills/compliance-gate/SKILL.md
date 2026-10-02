---
name: compliance-gate
description: Pre-commit and in-session compliance check against the project's Scala skills (mechanical ripgrep/Scalafix/scalafmt layer + LLM-judgment review). Use before declaring a unit of work done (about-to-commit, about-to-PR) and after any rebase or follow-up commit touching Scala source.
---

# Compliance Gate

The gate is the authoritative pass that says "this diff is compliant with the project's Scala skills". It runs in two surfaces:

- **In-session** (`go`, this skill) — Claude reads the protocol below, evaluates applicability from the diff, invokes the bundled executable for the mechanical layer, and adds an LLM-judgment pass over each applicable skill's body. This is the broader pass — it catches the rule categories the mechanical layer cannot express (combinator choice, layered architecture, naming, recovery vs root-cause-fix, etc.).
- **Pre-commit** (shell, deterministic) — the bundled executable runs as a `.git/hooks/pre-commit` shim and enforces the mechanical layer. Cheap, fast, fully reproducible. Blocks on `error` severity.

The mechanical layer's source of truth is per-skill frontmatter (`triggers:`, `mechanical_checks:`) inline in each `SKILL.md`. There is no central manifest and no per-project override.

## When to invoke

Run the gate:
- Before each commit (Claude in-session; the hook also runs in shell).
- Again before opening a PR (the in-session pass catches LLM-judgment rules the hook can't).
- After any branch rebase or follow-up commit that touched Scala source.

**The pre-PR pass must use `--diff branch`.** Per-commit passes see only what that commit touched, so a
file added early in a branch and never touched again is invisible to every later `staged`/`workdir` run —
including the last one. A branch that has been reporting clean commit after commit can still have errors
waiting in `--diff branch --base <resolved-base>`; that range, not the tip, is what a reviewer sees.

Skip the gate when the diff touches no `*.scala` / `*.sc` files. The hook does this automatically via its fast path; in-session, check `git diff --name-only` first and exit immediately if nothing Scala-ish changed.

## Protocol (in-session)

Claude performs these steps in order. Use TodoWrite to track them when the diff is non-trivial.

### 1. Resolve the diff

- `git diff $(git merge-base HEAD <base>)...HEAD --name-only` for the committed portion, plus `git diff HEAD --name-only` for unstaged work. Label each finding by source (committed / unstaged).
- Base ref resolution order: `origin/master` → `origin/main` → `master` → `main`. If none exist, fall back to "all commits on HEAD" with a banner; user can override via `compliance-gate --base <ref>`.

### 2. Enumerate changed files

Classify as **added** / **modified** / **deleted**. Deleted files cannot be grepped — record them so the LLM-judgment pass against `refactor-completeness` can check for caller orphans.

### 3. Determine applicable skills

For each skill under `.claude/skills/<name>/SKILL.md` (relative to the project root — skills are symlinked into every project):

1. Parse the `triggers:` block from frontmatter (the schema is in `compliance-gate/schema/skill-frontmatter.schema.json`).
2. For each trigger entry, evaluate predicates against changed files:
   - `file_patterns` — gitignore-style globs
   - `imports` — regex over the first ~100 lines of the file
   - `dependencies` — token grep against the project's `build.sbt` / `project.scala` (cached per session)
3. A trigger entry matches when `match: any` (default) and any predicate matches, or `match: all` and every predicate matches for the same file.
4. The skill applies if any trigger entry matches any changed file.

Process skills (`reuse-first`, `fail-loud`, `factor-out`, `refactor-completeness`) declare broad triggers (`file_patterns: ['**/*.scala', '**/*.sc']`) and almost always apply.

### 4. Load applicable skill bodies

The session preload exposes each skill's name and one-line description. For the LLM-judgment pass, load the full body of every applicable skill (via `Skill` tool) before reviewing — the rubric must be in context.

### 5. Run the mechanical layer

Invoke the bundled executable:

```
.claude/skills/compliance-gate/bin/compliance-gate \
  --json --diff branch --base <resolved-base> \
  --project-root <project>
```

`--diff` accepts exactly `staged | workdir | branch` (`branch` diffs `<base>...HEAD`, i.e. from the merge-base, committed work only; `workdir` diffs `HEAD` against the working tree **and lists untracked files as added**). A review of a branch *plus* its uncommitted work runs `branch` and `workdir` both: `branch` alone never sees a file that has not been committed yet. An unrecognised value exits 2 with an error — it does not fall back.

The rules come from the skills directory the executable itself lives in, so invoking it by its absolute path works from any checkout or worktree, inside the spine or not. `--skills-root` (or `SPINE_SKILLS_ROOT`) overrides that, and must name a directory holding `compliance-gate/SKILL.md`. A root that is not a gate skills root, a root with no rule-bearing skill, or a `--skills` name matching no skill exits 2 — an empty `findings` list always means the rules ran and found nothing.

It returns a JSON document `{ findings: [...], summary: { error, warning, info } }`. Capture the findings.

### 6. LLM-judgment pass

For each applicable skill, re-read the diff with the skill's body as the rubric. Identify violations the mechanical layer cannot express, including:

- **`scala`** — `==`/`!=` between domain types where `===`/`=!=` (Cats `Eq`) is the project idiom; `summon[X]` calls coexisting with `using` parameters in the same module (pick one consistently); abbreviated identifiers (`avgX`, `reqY`, single-letter lambda params); explicit type ascriptions on local `val`s or local `def`s (the private/protected member case is now caught mechanically — local scope is what's left for judgment); a bare `type FooId = UUID` / `= String` domain-id alias where a refined newtype (`RefinedSubtype[UUID, Pure]`) would stop two structurally-identical ids from interchanging at a call site; a cohesive cluster of helpers / an algorithm family left as loose files in a flat package instead of a domain-named sub-package (with tests); comment-policy nuances the Scalafix rule can't reach — it flags every comment token, so what's left for judgment is config values or thresholds quoted in prose/docs, and a sanctioned-exception comment (a tuning derivation, a genuinely surprising why) that doesn't actually earn its place (see `scala#comments`); raw numeric seconds/millis where `FiniteDuration` + duration literals belong; an `Option` field encoding a flow stage instead of separate per-stage types; a file accumulating multiple nameable concerns (god-file) that should be split.
- **`cats`** — wrong combinator chosen; `flatMap` chain that should be `mapN`; fold-accumulator that should be `foldMap`; sequential/nested `flatMap`s that should be a `for`/`yield`; deep nesting that survives a for-comprehension and wants a named `def` per stage.
- **`cats-effect-io`** — wrong typeclass (using `Async[F]` when `Sync[F]` suffices); side-effect not wrapped in `IO`.
- **`cats-effect-resource`** — multi-step release that should be split; finalizer not idempotent.
- **`cats-mtl-typed-errors`** — `handleErrorWith` / `.recover` / `.attempt` placed inside an active `Handle.allow`/`rescue` scope, trapping the exception form of a `Raise`.
- **`server-architecture`** — Service depending on another Service (must be an algebra); business logic in a Service; error-wrapping missing at algebra boundary; wire-shape DTOs declared in `api` instead of `shared/model`; frontend code importing from `api.*`; a numeric tuning threshold hardcoded in algebra/extension code that belongs in `application.conf`; a domain concept parked in the `config` package; an algebra shipped without its trait (`object` with concrete logic); a one-use named `*Impl` class with no helper bundle where an anonymous instance in the companion `apply` would do; everything bundled under a flat `alg`/`services` package instead of domain subpackages with the trait at the root and impls inside; related tunables left as flat config fields instead of a nested subconfig.
- **`fail-loud`** — `getOrElse(default)` masking a missing-data case; `recover` masking a bug whose root cause should be fixed; `recover` on a non-transient external failure; a side effect in a `for` that runs even when an upstream validated step failed.
- **`factor-out`** — repeated 2-3-line idioms on a domain type that should be lifted to an `extension` block; constants/fixtures duplicated across the diff; a cohesive cluster of helpers that should move into a domain-named sub-package with its own tests.
- **`iron`** — `.refineUnsafe` / `.assume` on a string literal where `autoRefine` would refine at compile time; custom extension methods that shadow `.option` / `.either` / `applyUnsafe`; a domain id modelled as a bare `type FooId = UUID` alias instead of a refined newtype (`RefinedSubtype[UUID, Pure]`); a raw `String`/`Int`/`Double`/`Long` field or parameter carrying domain meaning (a unit, a range, a format, an identity) where a refined type — existing or new — belongs; redundant `.value` on a transparent subtype.
- **`testing`** — expected values re-computed in the test instead of static/sampled; bare magic numbers where `sampleSome`/named values belong; a redefined generator that already exists in the shared generators object; a natural invariant tested with one hand-picked example instead of a property; extracted-worthy pure logic left inline and untested; hand-rolled `Fake*` classes where the mocking library serves.
- **`refactor-completeness`** — schema-changed-without-config-parity; plumbed-but-not-wired parameter; missing test for new component; signature widened but existing tests still exercise only the old narrow case; stale comment/README.
- **`reuse-first`** — helper added that duplicates an existing extension; dep added that's already on the path; Java interop (`java.util.regex.Pattern`, `scala.io.Source`) used where a Scala-native idiom (`scala.util.matching.Regex`, os-lib `os.read.lines`) exists.
- **`monocle`** — 3+ nested `.copy(…)` chain that should be an optic.
- **`waypoint`** — `currentPageSignal.map` should be `SplitRender`.
- **`laminar`** — controlled-input missing; `.now()` inside a combinator without `withCurrentValueOf`; transient signal failure unrecovered; `child <-- signal.map { case A => ... case B => ... }` on a sealed-ADT signal where `splitMatchOne` is correct; hand-rolled `collect { case Right(_) => _ }` instead of Laminext `collectRight`; a non-component (model/logic/service) placed in `components/`; presented business data computed ad hoc in the view instead of flowing through the viewmodel; `Var`s/signals threaded as method params where class-scope applies; hardcoded user-facing strings bypassing i18n.
- **`logging`** — log statement inside pure code / repository; secret value being logged; logging at non-boundary.

The mechanical rule list per skill lives in each skill's `mechanical_checks:` frontmatter; the gate's `README.md` reproduces the full list.

### 6b. Necessity pass — the whole diff against its intent

The per-skill pass asks "is this idiomatic?", one rubric at a time. It never asks "does this need to exist?", so a
diff can be idiomatic line by line and still carry machinery its goal never required. Run this pass once over the
**whole** diff, not per skill.

First write down the intent in one or two sentences, from the PR body, the task file or the commit messages. Then,
for every added type, field, parameter, endpoint, helper, overload, config knob and state variable, name the goal it
serves. If you cannot name one, it is a finding. Look especially for these:

- **Work moved across a boundary when the other side already holds the data.** A server-computed field, or a new
  endpoint or DTO field, that the client could derive from what it already receives. The reverse also counts.
- **A second path to the same result.** Two render paths for one entity, two definitions of one derived value (a
  bearing, a distance, a colour), or a copied overload beside the original.
- **A hand-rolled version of something the repo or a dependency already provides.** A TTL map where a cache library
  is already used, a new error-recovery helper beside an existing `guarded*`, or a test helper that re-implements the
  framework's own waits.
- **State or persistence nothing can read.** For example, a value persisted across reloads when its only reader
  needs data that does not survive the reload. Watch for such a value that also forced structural glue, like a split
  `combine` or named-tuple plumbing.
- **Instrumentation the intent never asked for.** New analytics events, outcome tables or extra log lines, and above
  all logs that pair an identity with precise locations or other personal data.
- **Abstractions with one use.** A single-caller wrapper, an enum that picks between two labels, an ADT case that
  production never distinguishes from another, a field nobody reads, or an unused codec or given.

For each finding, report the addition (`file:line`) and the existing data, helper or dependency that already covers
it (with its path). Also give the simpler alternative, roughly how many lines it saves, and whether removing it
changes behaviour.

Then list what *looks* complex but is warranted, and why, so the next reviewer does not re-derive it.

Verify every finding against the code before reporting it. A necessity finding is a decision for the user, not a
defect to fix silently.

### 6c. Jargon pass — names and prose a newcomer can read

Run this once over the whole diff: code identifiers, config keys and env vars, metric names and attribute values, log
text, test names, the README and the PR body. A name should say what the thing is. Report a name that would only make
sense to someone who has already read the code. Look especially for these:

- **Metaphor or shorthand for a concept that has a plain name.** Examples: "ask" for a requested distance,
  "ladder"/"rung" for a ranked list and a position in it, "filler", "strut", "chain", "footprint", "copies",
  "claim".
- **A misleading word.** Examples: `cost` holding a visited-node count; one word with two or three meanings in the same
  module (such as "reach"); a word that already means something else in the domain (such as "ground" for unshared
  distance, when it already means surface, or "pitch" for row spacing); a label left over from a removed alternative
  (such as `Direct` once `Rescaled` is gone).
- **A near-identical name.** Examples: `ZoneDurationTolerance` / `ZoneDurationTolerances` / `ZoneKeyedDurationTolerance`,
  or two names for one concept across files (`FixedLeg` against `ChainLeg`, `Marker` in code against "attribution" in
  the README).
- **Terse or abbreviated names.** Examples: `miss`, `realised`, `sized`, `ofBearing`, `programFirst`.
- **Agency given to a data structure in prose.** "A seed corrects itself" or "a seed picks again": say what the search
  or the code does to it.
- **A protocol verb used where the domain verb belongs,** such as "PUT" in test names for "append".

Leave established domain vocabulary alone (heading, bearing, leg, waypoint, node, edge, FTP, zone, grade), and a term
the README defines in plain words the first time it appears.

For each finding, report `file:line`, the term, what it means (checked in the code), a proposed name, and the
rename's reach: how many sites, and whether it is a config key, env var, metric name, wire field or i18n key that
something outside the code may read. When those names are not yet deployed, renaming them is free.

### 7. Merge & dedupe

Combine mechanical + LLM-judgment findings. Key by `(file, line, rule)`; if the same site appears in both, keep the mechanical one (it has precise line:col).

### 8. Render the report

Markdown structure:

```markdown
## Compliance gate — <ISO timestamp>

Base: `<base ref>`  Diff: <N> files (<modified>, <added>, <deleted>)
Applicable skills: <comma-separated names>

### Summary
| Severity | Mechanical | LLM-judgment | Total |

### <skill name> (<count> findings)
- **[<severity>]** `<file>:<line>` — `<rule-id>`
  <message>
  Suggested fix: <when mechanical> / <when LLM has a concrete suggestion>

### Deleted files
- <path>  — flagged for caller-orphan check (refactor-completeness)

### Next steps
1. Apply mechanical fixes (N errors).
2. Re-evaluate the LLM-judgment errors for <files>.
3. Re-run `compliance-gate`.
```

### 9. Iterate — and sweep

Apply fixes (mechanical first — they're unambiguous; then LLM-judgment ones). Re-run from step 1 until the gate is clean. The gate is the verification boundary; treat passing it as a precondition for declaring work done.

**Sweep every fix.** A finding names one site; the mistake almost never lives at one site. After fixing it, `rg` the offending pattern across the whole repo — not just the diff — and fix every sibling occurrence in the same pass. For large sweeps (a naming change, a packaging move, dozens of hits), delegate to a subagent with the pattern and the fix as its brief. A fix without its sweep is the single most repeated review complaint.

## What the gate does NOT do

- Rewrite git history.
- Push to remote.
- Run tests.
- Mutate files without explicit user confirmation (no auto-apply of scalafmt rewrites; the gate calls `scalafmt --test`, not `scalafmt`).

## Bypass

For a single commit the user can set `SPINE_GATE_DISABLE=1` — the executable exits with code 3 plus a stderr note, and the hook shim treats code 3 as commit-allowed. Bypassing is a deliberate act, recorded in shell history; no commit-message ceremony is needed.

## Adding a new mechanical rule

See `compliance-gate/README.md` for the developer guide. The short version:

1. Decide the tool (ripgrep / Scalafix / scalafmt).
2. Add the rule to the relevant skill's `mechanical_checks:` block.
3. Drop `bad.scala` + `good.scala` under `compliance-gate/fixtures/<rule-id>/`.
4. Run `compliance-gate --self-test` — the gate flags `bad/` only.

## Adding a new skill

The new skill's `SKILL.md` gains `triggers:` and (optionally) `mechanical_checks:` frontmatter. The gate discovers it automatically — no central registration step. Skills without `triggers:` are in-session-only (no mechanical layer).
