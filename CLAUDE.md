# Spine — Workspace Context

This is the spine meta-repository that orchestrates context across multiple independent projects.
Each project lives under `projects/` as its own git repository. The spine tracks orchestration
only — planning context, cross-cutting guidelines, and custom tooling.

## Projects

Projects live under `projects/`. Read each project's own `CLAUDE.md` for its purpose,
architecture, and conventions. 
  > `projects/` is gitignored, and each subdirectory is an independent git repository. 
  > Define per-project prefix shorthands in `projects/projects.md` as projects are added.

## Navigation

Start each session by:
1. Reviewing the applicable skills under `.claude/skills/`.
2. Listing `tasks/active/` for awareness of in-progress work.
3. Opening the relevant project's own `CLAUDE.md` for per-repo conventions.

## Task Prefixes

Use a short prefix in task filenames to signal scope at a glance:

| Prefix | Scope |
|--------|-------|
| `<abc>-` | Single project (use a three-letter identifier matching the project directory name) |
| `x-`   | Cross-cutting (touches 2+ projects) |

## Task Workflow

> **Sweep rule** — when fixing any flagged defect (review comment, gate finding, self-noticed
> antipattern), search the repo for sibling occurrences and fix them all before declaring done.
> One-site fixes to repo-wide patterns are the most repeated review complaint.

1. **Plan** — discuss the problem with an agent, explore codebases, surface edge cases.
2. **Commit to a task file** — dump key decisions, important files, and a phased checklist
   into `tasks/active/` using `tasks/TEMPLATE.md`. This persists the planning context.
3. **Execute** — start a fresh session, load the task file + relevant project `CLAUDE.md`
   files, and work (or spawn parallel agents with focused scope).
4. **Archive** — move completed task files to `tasks/completed/`.

## Structure

```
projects/          # Independent git repos (gitignored by this repo)
  projects.md         # Registry of project directories and prefixes
tasks/             # Task files (gitignored)  
  TEMPLATE.md      # Copy this to start a new task
  active/          # In-progress work
  backlog/         # Queued work
  completed/       # Done (kept for reference)
.claude/
  skills/          # Custom skills (tracked)
    scala/                   # Scala 3 style: braceless syntax, naming, comments policy, functional idioms, using params
    testing/                 # MUnit + ScalaCheck: properties, generators, sampleSome, static expectations, mock hygiene
    cats/                    # Core typeclass combinators: Functor/Applicative/Traverse/Foldable/Validated/MonadError
    cats-effect-io/          # Wrapping effects, Sync/Async/Temporal, blocking I/O, fibers
    cats-effect-resource/    # Resource lifecycle, acquire/release, bracket
    cats-mtl-typed-errors/   # Raise/Handle typed errors without EitherT
    laminar/                 # Reactive UI: split, controlled, signals, Var, Airstream patterns
    iron/                    # Refined types, opaque newtypes, constraints, Skunk/Circe/Tapir integration
    tapir/                   # Type-safe endpoint definitions, http4s server, STTP client, schema derivation
    skunk/                   # Functional PostgreSQL: codecs, queries, commands, transactions, Iron integration
    http4s/                  # HTTP server: routing DSL, middleware, Tapir wiring, Netty/Ember builders
    fs2/                     # Functional streams: pipelines, groupWithin batching, parallelism, concurrency
    monocle/                 # Optics: focus macro, Lens/Prism/Optional, nested immutable updates
    waypoint/                # Client-side routing: Page ADT, URL DSL, Router, SplitRender, navigation
    server-architecture/     # Layered design: Services, Algebras, Repositories, Logic traits
    logging/                 # log4cats placement, log levels, secret-safety, operation boundaries
    types-first/             # Type-driven design: make illegal states unrepresentable, parse-don't-validate, encoding table
    reuse-first/             # Pre-coding survey: find existing extensions, deps, combinators before writing
    factor-out/              # Anti-duplication pre-PR pass: fixtures, constants, repeated blocks
    fail-loud/               # Surface failures via Option/Either/Validated; no silent fallbacks
    refactor-completeness/   # Pre-PR audit: diff-audit, schema/config parity, missing tests, stale docs
    compliance-gate/         # Pre-commit + in-session compliance check: mechanical layer + LLM-judgment review
  commands/        # Custom slash commands (tracked)
```
