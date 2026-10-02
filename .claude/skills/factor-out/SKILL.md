---
name: factor-out
description: Anti-duplication pre-PR pass. Use during/after writing code that introduces new constants, test fixtures, helpers, or string/logic patterns — to spot within-PR duplication, against-existing-code duplication, and 3+-occurrence extraction opportunities before opening the PR. Test fixtures and constants are the top offenders.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc', '**/*.py']

mechanical_checks:
  - id: factor-out.no-utils-object
    tool: ripgrep
    severity: warning
    pattern: '^\s*object\s+Utils\b'
    paths: ['**/*.scala']
    message: 'Cross-cutting utilities belong in a domain-aware module, not `Utils.scala`'
    references: ['factor-out#placement']
  - id: factor-out.repeated-numeric-literal
    tool: ripgrep
    severity: info
    pattern: '\b[0-9]+\.[0-9]+\b'
    paths: ['**/*.scala']
    aggregate: by_value
    threshold: 3
    message: 'Repeated numeric literal across files — consider a named constant or refined type'
    references: ['factor-out#named-constants']
  - id: factor-out.duplicate-import-block
    tool: ripgrep
    severity: info
    aggregate: by_import_block_hash
    threshold: 2
    pattern: '^import\s+\S+'
    paths: ['**/*.scala']
    message: 'Two files share the same import block — likely close enough in concern that the code belongs together'
    references: ['factor-out#placement']
---

# Factor-Out — Don't Open a PR with Duplication

## Quick start
- **The "wrote it twice" rule**: when your diff itself introduces the same constant, literal, fixture-construction, or 3+-line block twice, extract it — don't wait for a third. (If the first occurrence pre-exists in the repo, the rule of three applies instead — see Decision rule.)
- **Test fixtures are the #1 offender** — scan sibling test suites for an existing default (`defaultConfig`, etc.) and use `.copy(field = ...)` for variance.
- **Bare numeric/string literals** repeated across files indicate a missing named value — extract to a companion / `*Constants` / refined Iron type.
- **Repeated 2-3-line idioms** become `extension` methods on the relevant type, near its definition.
- **Pre-PR diff scan** — eyeball `git diff` for repeated literals, identical blocks, and identical import lists across new files.
- **When NOT to factor out**: three near-identical lines that will diverge soon; the test is whether the extracted helper would need a parameter for *every* line of its body.

## Why
Duplication is the most-recurring class of review feedback. The shapes that recur:
- A fixture record (a profile, a config) hand-constructed across a dozen-plus test suites instead of one shared default + `.copy`.
- A bare literal (a magic number, a sentinel value) repeated in two guards within the same change.
- The same string/index juggling reimplemented in three query or service files when an extension on the domain type would do.
- "Extract this function"; "this probably belongs in a central place such as an extension on the type" — the same comment, over and over.

## The three flavours of duplication

### 1. Within-PR duplication
The new diff itself defines the same thing in two places.
- Same fixture builder block repeated across two test files in the PR → extract to `*Fixtures.scala` / `conftest.py`.
- Same numeric literal in two methods → name it.
- Same `flatMap` chain in three definitions → one helper.

### 2. Against-existing-code duplication
The new code reimplements something that already lives in the repo. Cross-link **`reuse-first`** — the survey should have caught this *before* writing. If you're seeing it post-write, you missed step 1; the fix now is to delete your duplicate and call the existing thing.

### 3. Extraction opportunity (rule of three+)
The same pattern now appears 3+ times across the codebase, including pre-existing code. Even if your PR didn't introduce them all, this is the right moment to lift them — your diff is the *third* occurrence.

## Where extracted code goes

| What you're extracting | Where it goes |
|---|---|
| Pure helper on a domain type (`OrderStatus`, `String` in domain context) | `extension` block next to the type, or `*Extensions` companion |
| Test fixture / default record | Shared `*Fixtures.scala` (Scala) / `conftest.py` (Python) at the lowest module that needs it |
| Numeric/string constant with semantics | Named `val` on a companion object; **better**: a refined Iron type |
| Repeated `flatMap`/`for`-comprehension shape | Method on the closest sensible service / algebra |
| Cross-cutting utility | Domain-aware module, **not** a generic `Utils.scala` |
| Cohesive cluster of helpers / an algorithm family (geometry, pricing, scoring) | A domain-named sub-package with its own test suite — see `scala#cohesive-sub-packages` |

## Pre-PR diff scan
Right before `git commit`, run `git diff <base>...HEAD` (resolve `<base>` as in `compliance-gate`: `origin/master` → `origin/main` → `master` → `main`) — or open the PR locally — and look for:
- The same literal value or string twice.
- Two `case class`-construction blocks with mostly the same fields (use `.copy`).
- Two test files whose `BeforeEach` / fixture construction is near-identical.
- Two new files with identical import lists — they likely belong in the same file or share a common base.

## When NOT to factor out
Per the project's "no premature abstraction" rule:
- **Three lines that look similar but will diverge soon** — wait. Three similar lines is better than the wrong abstraction.
- **A helper that needs a parameter for every line of its body** — the abstraction is a tautology; leave the duplication.
- **Glue code at boundaries** — wiring code that happens to look similar across two endpoints is fine; over-extracting wiring leads to indirection without clarity.

## Decision rule
For every block in your diff:
1. Does this exact shape appear elsewhere in the diff? → extract.
2. Does it appear elsewhere in the repo (search before assuming no)? → call the existing one.
3. Is this the third site of a pattern that pre-existed? → consider extracting now.
4. Otherwise (the pattern has one pre-existing site and your diff adds the second) → leave it; the rule of three tolerates a second occurrence when you didn't write both.

## Red flags — stop and extract
| Excuse | Reality |
|---|---|
| "I'll extract it in a follow-up PR" | The follow-up PR rarely happens; the reviewer flags the duplication now. |
| "It's only two occurrences" | When your diff wrote both, two **is** the threshold — that's the wrote-it-twice rule. |
| "The diff is small enough to eyeball" | Fixture duplication hides across files; run the pre-PR scan regardless of size. |
| "These blocks aren't *exactly* identical" | `.copy(field = ...)` on a shared default exists precisely for near-identical fixtures. |
| "They'll diverge soon" (without evidence) | Legitimate only with a concrete upcoming change in mind; otherwise it's deferral in disguise. |
| "Extracting would slow this PR down" | The extraction is minutes; the same review comment recurring across PRs is the slow path. |

## Cross-references
- `reuse-first` — pre-coding survey to catch flavour 2 *before* writing.
- `fail-loud` — duplicated sentinel values in the diff (e.g. `Duration(1)` in two guards) are a signal to consolidate **and** re-question the fallback.
- `iron` — for repeated bare numerics, a refined type is often the right home.
- `scala` — file-per-class convention applies once you extract.
