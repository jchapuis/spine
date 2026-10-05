# compliance-gate — developer guide

This skill ships an in-session protocol (`SKILL.md`), a Scala-CLI executable (`bin/compliance-gate`), an installer (`bin/install-gate`), a frontmatter schema (`schema/skill-frontmatter.schema.json`), custom Scalafix rules (`rules/scalafix/`), and per-rule fixtures (`fixtures/`).

Source of truth for the rule corpus is **per-skill frontmatter** in each `SKILL.md` under `.claude/skills/`. There is no central manifest — adding a rule means editing the skill that owns it.

## Layout

```
compliance-gate/
  SKILL.md                              # in-session protocol (Claude reads this)
  README.md                             # this file
  bin/
    compliance-gate                     # Scala-CLI wrapper, called by hook and in-session
    install-gate                        # POSIX shell, installs the hook shim into a project
    kev-serve                           # POSIX shell, serves the local kev model abide queries
    abide-enable                        # POSIX shell, turns abide's per-edit checks on in a project
  src/                                  # Scala-CLI source
    ComplianceGate.scala                # main + @main entry (mainargs)
    Diff.scala                          # git diff parsing
    Frontmatter.scala                   # YAML reader, derives to case classes (scala-yaml)
    Triggers.scala                      # applicability evaluation
    Rules.scala                         # ripgrep / Scalafix / scalafmt runners
    Report.scala                        # human + JSON output (upickle)
    SkillsRoot.scala                    # skills-root resolution and validation
    Cli.scala                           # CLI argument types
  project.scala                         # Scala-CLI directives (deps, compiler flags)
  rules/
    scalafix/                           # custom Scalafix rules (Phase C)
      NoFinalCaseClass.scala
      CatsBeforeIronWildcard.scala
      LaminarRequireDistinctOnPrimitive.scala
      MonocleNoExplicitLensConstruction.scala
  schema/
    skill-frontmatter.schema.json       # JSON Schema for the YAML extension
  templates/
    pre-commit.sh                       # the shim installed into <project>/.git/hooks/
  fixtures/
    <rule-id>/
      bad.scala
      good.scala
```

## How to add a mechanical rule

1. **Pick the tool.**
   - `ripgrep` — fastest, regex-based, no AST. Use for patterns that are unambiguous from text (banned tokens, banned method calls, specific syntactic shapes).
   - `scalafix` — when the rule needs Scala AST awareness (import ordering, method-receiver type, signature matching). Authored under `rules/scalafix/`.
   - `scalafmt` — formatting-only enforcement (line width, syntax rewrites). Configured per-project via `.scalafmt.conf`.

2. **Add the rule to the owning skill's `mechanical_checks:`.**

   For a ripgrep rule:
   ```yaml
   - id: <skill>.<short-name>
     severity: error          # error | warning | info
     tool: ripgrep
     pattern: '<PCRE2>'
     files_must_import: ['com.raquo.laminar']   # optional; restricts to files importing one of these
     paths: ['**/src/main/**/*.scala']          # optional; gitignore-style globs
     paths_exclude: ['**/target/**']            # optional
     aggregate: by_value                         # optional; by_value | by_import_block_hash
     threshold: 3                                # optional; for aggregate rules
     message: '<one-line>'
     references: ['<skill>#<anchor>']
   ```

   For a Scalafix rule:
   ```yaml
   - id: <skill>.<short-name>
     severity: error
     tool: scalafix
     rule: 'NoFinalCaseClass'   # corresponds to rules/scalafix/NoFinalCaseClass.scala
     message: '<one-line>'
   ```

   For a scalafmt rule:
   ```yaml
   - id: <skill>.scalafmt
     severity: error
     tool: scalafmt
     config: '.scalafmt.conf'   # config filename, resolved per file (see below)
     message: 'Run scalafmtAll'
   ```

   `config` is a **filename, not a root-relative path**. For each changed `.scala` file the gate walks up from the file to the project root and uses the nearest ancestor config, then runs scalafmt once per config group. That is what makes per-module layouts work (`app/.scalafmt.conf`, `infra/.scalafmt.conf`, `infra/core/.scalafmt.conf`) as well as single-config repos, where the walk-up simply lands on the root config. Files with no config in any ancestor are skipped, and the skip message names them.

3. **Add fixtures.**

   Drop `compliance-gate/fixtures/<rule-id>/bad.scala` and `compliance-gate/fixtures/<rule-id>/good.scala`. The fixture name matches the rule `id`.

4. **Verify.**

   ```
   compliance-gate --self-test
   ```

   The gate validates every skill's frontmatter against the schema, runs every fixture pair, and parses a set of `git diff --name-status` lines (including renames and paths with spaces). A fixture must produce exactly the expected violation count.

## Trigger format

Triggers control applicability. The schema:

```yaml
triggers:
  - match: any                                # any (default) | all
    file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['com.raquo.laminar']            # regex over first ~100 lines
    dependencies: ['laminar']                 # token grep against build.sbt / project.scala
    paths_exclude: ['**/target/**']
```

A trigger entry matches a file if (depending on `match`):
- `any` — at least one predicate (file_patterns, imports, dependencies) is satisfied for that file.
- `all` — every predicate is satisfied for that file.

Multiple trigger entries compose as OR. A skill applies if any entry matches any changed file.

Process skills (`reuse-first`, `fail-loud`, `factor-out`, `refactor-completeness`) declare:

```yaml
triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
```

— broad applicability, evaluation is essentially free.

## Scalafix integration

Custom Scalafix rules live under `rules/scalafix/<RuleName>.scala` as syntactic rules (Scalafix `v1` API, Scala 2 syntax — Scalafix compiles them on the fly via its embedded compiler). The gate's runner resolves a `mechanical_checks` entry with `tool: scalafix` and a non-`DisableSyntax.*` rule name to a `file:` URL pointing at the source file, and Scalafix loads it directly — no JAR build, no classpath wiring.

Each custom rule file must:
1. Start with a `/* rule = <RuleName> */` header so Scalafix can register the rule's identifier.
2. Declare `package compliancegate.scalafix` (or another package) and extend `scalafix.v1.SyntacticRule("<RuleName>")`.
3. Reference `import scalafix.v1._` and `import scala.meta._` for the AST API.
4. Be self-contained — one file per rule. Scalafix compiles the file at invocation time, so dependencies are restricted to scalafix-core, scala.meta, and the Scala 2 standard library.

The rule name in the `mechanical_checks` entry (`rule: 'NoLocalTypeAscription'`) must match both the `SyntacticRule("...")` constructor argument and the filename (`NoLocalTypeAscription.scala`). Scalafix output emits `[NoLocalTypeAscription]` for each finding, and the gate's `Scalafix.parseOutput` maps that back to the originating check.

Built-in `DisableSyntax.*` rules continue to work as before — they're configured via the standard `DisableSyntax.noFoo = true` HOCON flags and need no source file.

Semantic rules (those needing `SemanticDocument` — type-aware analysis like cross-file references, signature matching, or inferred-type inspection) are still **deferred**. They require the target project to compile with the SemanticDB plugin, which the gate cannot assume; the in-session LLM-judgment pass is the fallback for that class of rule.

### Adding a new Scalafix rule

1. Author `rules/scalafix/<RuleName>.scala` per the contract above.
2. Add the `tool: scalafix` entry with `rule: '<RuleName>'` to the owning skill's `mechanical_checks:`.
3. Drop `fixtures/<skill>.<rule-id>/{bad,good}.scala` for human reference. (Note: the self-test does not yet auto-validate Scalafix fixtures — only ripgrep fixtures get pattern-matched. Validate Scalafix rules manually by running the gate against the fixture pair in a throwaway git repo.)
4. Iterate by running `compliance-gate` against the fixture; tune the rule's AST matching until `bad.scala` flags and `good.scala` doesn't.

## Skills root

The gate reads every `SKILL.md` under one skills root, and its custom Scalafix rules from `<root>/compliance-gate/rules/scalafix/`. The root is `--skills-root` if given, else `SPINE_SKILLS_ROOT`. `bin/compliance-gate` defaults `SPINE_SKILLS_ROOT` to the skills directory it lives in (`bin/../..`), so the rules always travel with the executable, whichever project or worktree it is pointed at.

A root qualifies only if it contains `compliance-gate/SKILL.md`; any other directory exits 2 naming where it came from. The gate also exits 2 when the root holds no skill with `mechanical_checks`, and when `--skills` names a skill that is not loaded. `--self-test` covers the root check against a lookalike skills directory.

## Severity defaults

New rules default to `error` (the design's locked decision — maximum discipline). For rules in the noise-tuning phase, set `severity: warning` explicitly until FP rate falls below 1-per-10-commits in pilot projects, then promote to `error`.

## CI

In the spine's own CI, run:

```
compliance-gate --self-test
```

This checks all frontmatter against the schema, runs every fixture pair, and exercises the diff parser and the skills-root check. Failing checks fail CI — the gate is itself gated.

## Upfront enforcement (abide + kev)

The gate is a **batch** check: it runs once, late (pre-commit / `/ck` / before a PR). [abide](https://github.com/coldteadotai/abide)
adds a **per-edit** layer that checks a curated subset of the gate's judgment rules on *every* edit a coding
agent makes, from outside its context window, and makes the agent repair a violation in the same turn. It does this
by asking a decision model one yes/no question per rule. Instead of the hosted model, we point it at
[kev](https://github.com/jaredpalmer/kev) — Jared Palmer's open re-implementation of that same API — served locally,
so no code leaves the machine and the model is ours to fine-tune.

```
agent edit ──▶ abide hook ──▶ POST /v1/systemone ──▶ kev (local)  ──▶ probability per rule
                 (rules from .abide/rubric.json)                        ≥0.8 → agent repairs now
```

**This layer is advisory and complementary. The compliance-gate stays authoritative** — if the two ever disagree,
the gate wins. Mechanical (ripgrep/Scalafix) rules and repo-wide judgments (`reuse-first`, `refactor-completeness`)
stay in the gate only; abide carries just the **self-contained, single-edit** judgment questions.

### Single source of truth

`.abide/rubric.json` (at the spine root, committed, symlinked into each project by `/projects-update`) is a
**hand-authored subset** of the judgment rules in `SKILL.md` §6 — each rule's `source` points back at the skill
that owns it. **Do not run `abide compile`**: it would read `CLAUDE.md`/`AGENTS.md` and create a second rule home.
When you change a §6 rule that abide carries, edit the rubric to match.

### Enabling it on a checkout

Needs Node ≥ 22, [uv](https://docs.astral.sh/uv/) and `jq`. Once per machine:

```sh
npm install -g @coldtea/abide                         # a stable path for the hook script
.claude/skills/compliance-gate/bin/kev-serve          # keep it running; first run clones kev and downloads the model
```

Once per project:

```sh
.claude/skills/compliance-gate/bin/abide-enable projects/<name>
```

`abide-enable` does four things:
- links the shared rubric into the project
- adds abide's three checking hooks to the project's personal `.claude/settings.local.json`, and keeps that file and
  the link out of its `git status`
- points abide at kev in `~/.abide/.env`, with a placeholder key, because kev needs none
- installs a `post-checkout` git hook that runs `abide-enable` on every new `git worktree add` of that repository

abide reads the rubric from the root of the checkout that holds the edited file, so a worktree without the rubric
link is silently unchecked. The `post-checkout` hook covers worktrees created after enabling; for ones that already
exist, run `abide-enable` on each (`git worktree list`). Edits made through a shell command rather than the agent's
edit tools never reach the per-edit hook — only the pre-commit gate sees them.

**Do not run `abide init` or `abide login`.** Besides the hooks above, `init` installs a SessionStart hook that asks
the agent to recompile the rubric from the project's `CLAUDE.md`, which would overwrite the shared rubric. `login` is
only for abide's hosted model. To switch it off again: `abide-enable --uninstall projects/<name>`.

Then start a new agent session and code as usual. A rule-breaking edit gets a repair request in the same turn,
unless you explicitly asked for that behaviour; the agent then says so and leaves it.

Serving Kev-4B needs about 32 GB of RAM. On a smaller machine, `KEV_MODEL=jaredpalmer/kev-0.8b` serves the stock
0.8B model, which is not fine-tuned and is far noisier.

To see what *would* fire without an agent, run `abide check <paths>` in the project. It reads the working-tree diff
and prints a verdict per rule. `.abide/events.jsonl` logs every verdict the hooks produced.

### The model

Stock Kev-4B was too noisy to use per edit. On clean real diffs it would have raised a note on about 1 in 8 rule
checks, and on a third of edits for logic placement alone. `kev-serve` therefore defaults to
`jchapuis/kev-4b-spine-scala`, Kev-4B fine-tuned on this rubric:
- held-out accuracy 0.70 → 0.97
- notes on clean real diffs 111 → 2
- forced repairs on clean real diffs 1 → 0

It is weak on `scala-plain-readable-names`, which still misses most naming violations, so the jargon pass stays the
real check for names. The training pipeline, data and full results live in `.abide/finetune/`.

**The model learns the rubric's exact question text.** If you edit a question in `.abide/rubric.json`, retrain
(see `.abide/finetune/README.md`).
