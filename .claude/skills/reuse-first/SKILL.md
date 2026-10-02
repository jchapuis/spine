---
name: reuse-first
description: Pre-coding survey discipline. Use BEFORE adding new helpers, validators, dependencies, or imperative iteration — to find existing extensions, transitive deps, language idioms, and combinators (defers to cats/cats-effect-io/monocle/iron/scala). Prevents reinventing what already exists in the project or its libraries.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
---

# Reuse-First — Look Before You Write

## Quick start
- **Step 0 — question necessity.** Before searching for how to write it, ask whether it should exist at all right now: no speculative flags, abstractions, or "for later" scaffolding. If there's no consumer today, don't write it (the pre-PR sweep for this is `refactor-completeness#e-necessity-audit`).
- Before adding a helper or extension, **search the project** with serena (`search_for_pattern`, `find_symbol`) for existing extensions on the type, sibling `*Extensions` objects, or near-duplicates. Fall back to `grep` when the symbol view doesn't cover the case.
- Before adding a dependency, **scan transitive deps** — `coursier resolve --tree` / `sbt dependencyTree` (Scala) or `pip show` / `uv pip list` (Python). Many libraries pull in what you need.
- Before writing imperative iteration / nested `if` / nested `flatMap`, **reach for a combinator**. The trigger list is below; the operator vocabulary lives in dedicated skills (cross-linked).
- Before writing a custom non-blank/non-empty/range validator, **check Iron**.
- Before threading the same dependency through every method, **use Scala 3 `using`/`given`**.
- Before reaching for a Java API, **check for the Scala-native idiom**. `java.util.regex.Pattern` → `scala.util.matching.Regex`; `scala.io.Source` → os-lib `os.read.lines`. Java interop is fine when no Scala idiom exists; prefer the Scala-native one when it does — it reads as project idiom and avoids the interop seams (`null`, checked exceptions) the rest of the code is built to avoid.

## Why
The single biggest source of PR review noise is "this already exists". The recurring shapes:
- String/index juggling reimplemented in several places when an extension already belonged on the domain type.
- A pattern (a regex, a derived constant) re-defined when it already lived on the type's companion.
- A hand-rolled CSV writer when the CSV library was already on the path as a transitive dependency.
- Hand-written non-blank validation when a refined type already carried the constraint via `.option`.

## Workflow

### Step 1 — Search before you write
For any helper / extension / utility you're about to write:
1. **Serena semantic search** — `search_for_pattern` on the body of the helper you'd write; `find_symbol` for any name resembling what you want. Look for:
   - `extension (s: String)` / `extension (status: OrderStatus)` blocks near the relevant types
   - `*Extensions.scala`, `*Helpers.scala`, `*Ops.scala` files in the same package
   - companion object methods with the same effect
2. **`grep` fallback** — when a textual fragment ("lastIndexOf('_')", "trim.nonEmpty", a regex literal) is more diagnostic than a symbol name.
3. If you find one — use it; possibly extend it (cross-link `factor-out` for extraction).

### Step 2 — Survey deps before adding
- `coursier resolve --tree` / `sbt dependencyTree` for Scala.
- `pip show <pkg>` / `pyproject.toml` for Python.
- A library you're about to add may already be on the path via a transitive (e.g. `fs2-csv` came in through `fs2-data`).

### Step 3 — Reach for combinators before reinventing
This skill carries only the **trigger list** — the operator names live in dedicated skills:

| Tell-tale in your draft | Defer to |
|---|---|
| imperative iteration / nested `if` / hand-rolled `Option`-flatMap chains | `cats` (collect, find, whenA, traverse, mapN, OptionT, EitherT, Validated.condNec, handleErrorWith, …) |
| blocking, cancellation, fibers, `bracket`/`guarantee` | `cats-effect-io` |
| resource lifecycle / `Resource.make` / acquire-release | `cats-effect-resource` |
| typed domain errors (`Raise`/`Handle`) | `cats-mtl-typed-errors` |
| nested `.copy(...)` chains | `monocle` |
| streams, batching with `groupWithin`, parallel evaluation | `fs2` |
| bespoke non-blank / non-empty / range / regex validation | `iron` |
| same dependency threaded through every method | Scala 3 `using` / `given` (see `scala`) |

### Step 4 — When the helper genuinely doesn't exist
Then write it — but place it where the next person will find it:
- Extension methods on the type live next to the type (or in a `*Extensions` companion).
- Cross-cutting helpers go in a domain-aware module, not a generic `Utils.scala`.
- See `factor-out` for the placement rules.

## Decision rule
Before writing the first line of a helper, ask three questions:
1. Does an extension/method on this type already do it? *(serena search)*
2. Is there a combinator I'm forgetting? *(table above → relevant skill)*
3. If I'm about to add a dependency: is something in the transitive closure already enough? *(dep tree)*

If any answer is yes → use it. If all are no → write the helper, and place it where step 1 would find it next time.

## Red flags — stop and search
| Excuse in your head | Reality |
|---|---|
| "Writing it is faster than searching" | The search is 30 seconds; the "this already exists" review round-trip is a day. |
| "This helper is too specific to exist already" | The recurring shapes under *Why* were all "too specific" — and all existed. |
| "I already know this codebase" | The extension may have landed last week. Repo knowledge expires; the search doesn't. |
| "I'll dedupe it later if a reviewer flags it" | "Later" is a follow-up PR that competes with new work and usually loses. |
| "Adding the dependency is simpler than checking the tree" | A duplicate dep ships two versions of the same concern; the tree check is one command. |
| "A quick loop is fine here" | Imperative iteration is exactly the tell-tale the combinator table exists for — scan it first. |

## Cross-references
- `cats`, `cats-effect-io`, `cats-effect-resource`, `cats-mtl-typed-errors`, `fs2`, `iron`, `monocle`, `scala` — defer-to skills for the actual operator vocabulary.
- `types-first` — before hand-rolling a validator or a domain primitive, check the encoding table; the right refined type / distinct newtype may already be the answer (and may already be defined).
- `factor-out` — when the helper does exist but is duplicated, or when you write the helper and the second caller appears.
