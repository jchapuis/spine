---
name: fail-loud
description: Surface failures explicitly via Option/Either/Validated/exceptions instead of hiding them behind silent defaults. Use when writing or reviewing code with fallbacks, sentinel values, optional handling, defensive guards, or error recovery — across Scala, Python, and any language where a "shouldn't happen" cushion can mask a real bug.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc', '**/*.py']

mechanical_checks:
  - id: fail-loud.from-list-unsafe
    tool: ripgrep
    severity: error
    pattern: '\bNonEmptyList\.fromListUnsafe\b'
    paths: ['**/*.scala']
    message: '`fromListUnsafe` throws — return `Option[NonEmptyList]` or `Raise` an empty-input error'
    references: ['fail-loud#non-empty-list-from-list-unsafe']
  - id: fail-loud.collect-string-narrow
    tool: ripgrep
    severity: warning
    multiline: true
    pattern: '\.collect\s*[{:]\s*case\s+\w+\s*:\s*String\s*=>'
    paths: ['**/*.scala']
    message: 'Silent type-narrowing on a dynamic value — coerce explicitly or surface the mismatch'
    references: ['fail-loud#silent-type-narrowing']
  - id: fail-loud.get-or-else-empty-string
    tool: ripgrep
    severity: warning
    pattern: '\.getOrElse\(""\)'
    paths: ['**/*.scala']
    message: '`.getOrElse("")` on a dynamic boundary — let absence remain `Option` instead of papering over it'
    references: ['fail-loud#sentinel-values-for-missing']
  - id: fail-loud.no-null
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noNulls'
    message: '`null` is a silent sentinel — use Option/Either/Validated to surface absence or failure'
    references: ['fail-loud#sentinel-values-for-missing']
  - id: fail-loud.no-throw
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noThrows'
    message: '`throw` bypasses the typed error channel — Raise / Either / IO.raiseError instead'
    references: ['fail-loud#surface-failures-via-types']
  - id: fail-loud.no-as-instance-of
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noAsInstanceOf'
    message: '`.asInstanceOf[T]` is a silent unsafe cast — pattern-match, refine, or surface the mismatch'
    references: ['fail-loud#silent-type-narrowing']
  - id: fail-loud.no-is-instance-of
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noIsInstanceOf'
    message: '`.isInstanceOf[T]` is the read-only half of an unsafe cast pair — pattern-match instead (`case t: T =>`)'
    references: ['fail-loud#silent-type-narrowing']
  - id: fail-loud.no-finalize
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noFinalize'
    message: 'Overriding `finalize` is unreliable — use `Resource` / `bracket` for guaranteed cleanup'
    references: ['fail-loud#surface-failures-via-types']
  - id: fail-loud.to-unit-sweep
    tool: ripgrep
    severity: info
    pattern: '\.toUnit\s*\(\s*(HOURS|MINUTES|SECONDS|MILLISECONDS|MICROSECONDS|NANOSECONDS)\s*\)'
    paths: ['**/*.scala']
    message: '`.toUnit(...)` repeats unit-extraction noise across call sites — consider a refined duration type with accessor extensions (`inHours`, `inMinutes`, `inSeconds`) and sweep siblings'
    references: ['fail-loud#sweep-when-you-fix']
---

# Fail-Loud — Surface Failures, Don't Bury Them

## Quick start
- `.getOrElse(default)` is allowed only when the default is **semantically correct**, never as a "shouldn't happen" cushion.
- No sentinel values (`Duration(1)`, `-1`, `""`) standing in for absence — use `Option`/`Either`/`Validated`.
- No dead-code defensive programming — if an invariant is enforced upstream, don't add a redundant guard downstream.
- Prefer `apply`/`xs(i)` over `lift(i).getOrElse(_)` when the index is supposed to exist by construction.
- For multi-field validation, use `ValidatedNec`/`ValidatedNel` to **accumulate** failures, not `Either` which short-circuits.
- No silent type-narrowing on dynamic/JS-interop values (`.collect { case s: String => s }`, `getOrElse("")` on optional dynamic properties) — coerce or fail.
- **Validate before destructive ops** — never `DROP TABLE` / delete files / overwrite state before the new payload has passed schema and content validation.
- **Sweep-completeness**: when you fix a silent fallback in one site, grep the package for the same shape and fix all siblings in the same PR.
- If you genuinely want a fallback, **log a warning** so silent degradation is observable.

## Why
Silent fallbacks are the most-flagged class of issue in code review. They turn bugs invisible: a placeholder value standing in for a missing duration, a derived field defaulted from an unrelated input, a re-used unfiltered list when an index is wrong, a `.collect { case s: String => s }` that silently drops every numeric-typed entry — none of these surface in tests, only in production behavior that "looks fine but isn't."

## Patterns

### 1. Sentinel values for "missing"
```scala
// Bad — Duration(1) means "I gave up parsing"
val duration: Duration =
  parseDuration(record).getOrElse(Duration(1))

// Good — absence is explicit
val duration: Option[Duration] = parseDuration(record)
```
If `duration` is later required, validate the Option at the boundary (turn it into a typed error) rather than papering over the missing case.

### 2. Silent fallbacks for "shouldn't happen"
```scala
// Bad — if profile.preferences is missing, we silently fabricate one from defaults
val prefs = profile.preferences.getOrElse(buildFallbackPreferences(defaults))

// Good — surface the missing-data case
def resolvePreferences(profile: UserProfile): Either[MissingPreferences, Preferences] =
  profile.preferences.toRight(MissingPreferences).map(resolve)
```

### 3. Dead-code defensive programming
```scala
val clamped = Math.max(rawValue, 1)              // invariant: ≥1
// Bad — getOrElse(1) is unreachable, masks future invariant breaks
val safe = Duration.either(clamped).toOption.getOrElse(Duration(1))

// Good — invariant is explicit; `assume` throws if it's ever broken
val safe = Duration.assume(clamped)
```

### 4. `apply` over `lift` when index is invariant
```scala
// Bad — if the selector ever returns a foreign index, the bug is invisible
items.lift(selectedIndex).map(remove).getOrElse(items)

// Good — throw on misuse so the bug surfaces immediately
val updated = remove(items(selectedIndex))
```

### 5. Aggregate validation errors
```scala
// Bad — first failure short-circuits, hides the rest
for
  id     <- OrderId.either(input.id)
  status <- OrderStatus.either(input.status)
yield Order(id, status)

// Good — accumulate all field-level errors
import cats.syntax.either.*    // toValidatedNec
import cats.syntax.apply.*     // mapN
(
  OrderId.either(input.id).toValidatedNec,
  OrderStatus.either(input.status).toValidatedNec
).mapN(Order.apply)
```

### 6. Log on intentional fallback paths
```scala
// Cosmetic default is fine, but make degradation observable
xs.headOption.fold(
  Logger[F].warn(s"empty list at $callsite, using default") *> default.pure[F]
)(_.pure[F])
```

### 7. Silent type-narrowing on dynamic / JS-interop values
On boundaries where values arrive as `js.Any`, `Object`, untyped maps, or parsed-JSON dynamic values, `.collect { case s: T => s }` and `getOrElse("")` look like validation but are silent filters. They drop every value whose runtime representation differs from your guess.

```scala
// Bad — silently drops every entry whose runtime type is numeric (Int/Long/Double)
val ids: List[String] = raw.collect { case s: String => s }

// Bad — every missing or non-string property becomes "" and flows downstream as "valid"
val name = props.get("name").getOrElse("")

// Good — coerce explicitly and validate; let absence remain absent
val ids: List[String]      = raw.map(_.toString)
val name: Option[UserName] = props.get("name").flatMap(UserName.option)
```
The fixture trap: a test fixture using one representation will pass while production data uses another. When writing fixtures for dynamic-typed boundaries, exercise **every representation the source schema can produce** (string AND numeric AND missing).

### 8. Gate side effects on upstream validation
A `for` that composes multiple effectful steps must keep downstream side effects gated on upstream validation. The two failure modes:
- **Destructive op before validation** — `DROP TABLE` / file delete / cache invalidate before the new payload is known well-formed. A throw mid-validation leaves good data destroyed with nothing to restore.
- **Side effect runs regardless of upstream failure** — an "apply augmentations" / "send notification" / "write artefact" step that fires even when the prior step's `Either`/`Validated` carried a failure. Failures stop short-circuiting via the typed channel because a downstream side effect throws over the top.

The rule: every effectful step downstream of a validated step must execute **only** when the validated step succeeded, and any failure of either must surface through the same channel.

```scala
// Bad — DROP runs before we know the upload is well-formed; bad payload nukes the live table
for
  _ <- repo.dropTable
  _ <- payload.validateSchema  // throws → table is gone, nothing to restore
  _ <- repo.load(payload)
yield ()

// Good — destructive step is gated on validation
for
  _ <- payload.validateSchema
  _ <- repo.dropTable
  _ <- repo.load(payload)
yield ()
```

```scala
// Bad — augmentations run even if the patch failed; if they throw, the typed PatcherError is short-circuited
for
  layerUpdates       <- patch.layers.parTraverse(applyLayer)
  augmentationUpdate <- layerAugmentations.adaptAugmentationsFor(location, patch)
yield layerUpdates.sequence.toEither.map(_ => ())

// Good — augmentations only run when layer updates validated; both failure modes flow through the same channel
for
  layerUpdates           <- patch.layers.parTraverse(applyLayer)
  validatedUpdates        = layerUpdates.sequence
  validatedAugmentations <- validatedUpdates.traverse(_ =>
                              layerAugmentations.adaptAugmentationsFor(location, patch))
yield validatedAugmentations.toEither
```

**Delete by exact name, not a prefix sweep.** When cleaning up tables, files, or keys, enumerate the exact expected names and remove those. A `LIKE 'foo%'` / prefix glob silently matches unrelated siblings when one name is a prefix of another — the expected set is almost always deterministic, so list it.

```scala
// Bad — prefix match also deletes `report-2024-final` when targeting `report-2024`
keys.filter(_.startsWith(prefix)).traverse_(store.delete)

// Good — the expected keys are deterministic; delete exactly those
val targets = List(s"$prefix.data", s"$prefix.index")
targets.traverse_(store.delete)
```

### 9. Sweep when you fix
A single silent-fallback fix is rarely the only instance. When the reviewer (or you) catch one site, grep the package for the same shape and fix every sibling in the same PR — not the next one. Stopping at the reviewer-flagged sites leaves the same bug in any peer file the reviewer didn't open.

**Common sweep patterns.** When one of these is the flagged shape, run the matching ripgrep across the package before opening the PR:

| Flagged shape | ripgrep query |
|---|---|
| `getOrElse` on dynamic data | `rg "\.getOrElse\(" --type scala` |
| `getOrElse` with a sentinel list | `rg "\.getOrElse\([^)]*[Ll]ist" --type scala` |
| `getOrElse` with `""` | `rg '\.getOrElse\(""\)' --type scala` |
| `.lift(i).getOrElse(...)` | `rg "\.lift\(\d+\)\.getOrElse" --type scala` |
| `.collect { case T => ... }` narrowing | `rg "\.collect\s*\{\s*case\s+\w+\s*:" --type scala` |
| `.toUnit(HOURS/MINUTES/...)` repeated unit-extraction | `rg "\.toUnit\(" --type scala` |
| Sentinel numeric literal (`Duration(1)`, `-1`, etc.) | `rg "\bDuration\(1\)\b\|\\-1\b" --type scala` |
| `final case class` / `final def` (Scala 3 noise) | `rg "\bfinal\s+(case\s+class\|def)\b" --type scala` |

Each one of these recurs in clusters — fixing one site without grepping for siblings leaves the bug class in the codebase, and the next reviewer surfaces the same finding next sprint.

### 10. Encode "no result" in the return type
When an algebra method *can* legitimately have no result (no candidates found, the input list is empty, the upstream gave back nothing), **lift the absence into the return type**. Don't paper over it with `NonEmptyList.fromListUnsafe`, `getOrElse(emptyList)`, or a sentinel "no-op" value — those make the empty case look like data and let it flow downstream as if it were valid.

```scala
// Bad — fromListUnsafe throws at runtime; getOrElse(empty) makes "nothing" look like a valid empty result
def topCandidates(query: Query): F[NonEmptyList[Candidate]] =
  searchRepo.search(query).map(NonEmptyList.fromListUnsafe)

def topCandidates(query: Query): F[List[Candidate]] =
  searchRepo.search(query).map(_.getOrElse(List.empty))

// Good — the absence is in the type; the caller decides how to surface it
def topCandidates(query: Query): F[Option[NonEmptyList[Candidate]]] =
  searchRepo.search(query).map(NonEmptyList.fromList)

// Better — surface as a typed error if "no candidates" is a domain failure
def topCandidates(query: Query)(using Raise[F, NoCandidates]): F[NonEmptyList[Candidate]] =
  searchRepo.search(query).map(NonEmptyList.fromList).flatMap:
    case Some(nel) => nel.pure[F]
    case None      => Raise[F, NoCandidates].raise(NoCandidates(query))
```

Cross-link `cats-mtl-typed-errors` for the `Raise`/`Handle` pattern; cross-link `iron`'s `.option` / `.either` for the same principle on individual values.

### 11. Don't wrap an error you can fix
Before adding `recover` / `handleErrorWith` / `try/except`, ask: is this error a symptom of a bug elsewhere, or a genuinely-expected failure (transient external dep, parse of untrusted input)? If it's a symptom, fix the root cause — the recover then becomes dead code and lives forever as a fossilised band-aid that hides regressions of the original bug.

```scala
// Bad — recover masks a bug whose root cause is upstream
val resilient = pipeline.recoverWith:
  case _: Throwable => fallback  // why does this throw? fix that instead

// Good — root cause fixed, no recover needed
val healthy = pipeline
```

Recovery is legitimate only for the second case (transient external failure, untrusted parse), and it belongs at the boundary where the failure originates — not as a downstream catch-all. Cross-link `laminar`'s "Recover transient signal failures" for the legitimate UI-side case.

## Decision rule
When you reach for `getOrElse`, `lift`, or a default value, ask:
1. **Is the default semantically correct?** (e.g. an empty list for "no items" — fine.)
2. Or am I suppressing a "this shouldn't happen" case? Then the right move is one of:
   - return `Option`/`Either`/`Validated` and force the caller to handle it,
   - throw via `assume`/`apply`/explicit exception,
   - log a warning and proceed (only if the degradation is truly cosmetic).

## Red flags — stop and surface it
| Excuse | Reality |
|---|---|
| "This default is harmless" | A harmless-looking default converts a crash you'd notice into wrong data you won't. |
| "This branch can't happen anyway" | Then make it loud (`assume`/`apply`) so the day it does happen is visible — a silent guard hides it. |
| "Logging the fallback is enough" | Logging covers *intentional, cosmetic* degradation only; missing required data needs a type or a typed error. |
| "I'll add `recover` to be safe" | A recover around a bug is the fossilised band-aid of pattern 11 — fix the root cause; recovery is for genuinely-expected failures. |
| "The reviewer only flagged this one site" | Flagged-site-only fixes leave the bug class alive — sweep the package (pattern 9). |
| "It's just a test fixture" | A fixture using one representation passes while production data uses another (pattern 7's trap). |
| "It used to crash here, so now it returns `Either`" | A terminal failure was doing your cleanup. Once the process survives, partial state, leaked handles and retry loops are yours to own — enumerate what the crash was silently providing before you remove it. |

## Cross-references
- `cats` — for `Validated.condNec`/`validNec`/`invalidNec`, `OptionT`, `Either.fromOption`, `ensure`/`ensureOr`.
- `cats-mtl-typed-errors` — when typed errors are part of the function signature, use `Raise[F, E]`.
- `iron` — for refined types that make impossible states unrepresentable at the type level (no fallback needed).
- `types-first` — the compile-time sibling: when an invariant *can* be encoded in a type, make the illegal state unrepresentable instead of guarding against it at runtime. Reach there first; reach here when it genuinely can't be encoded.
- `factor-out` — duplicated sentinel values across the diff are a signal to consolidate AND re-question the fallback.
- `refactor-completeness` — when removing a fallback, audit its callers for newly-exposed `Option`/`Either` cases.

## Python flavour
Same principle, different idioms:
- Raise an exception over returning `None` for "missing required data"; let the caller catch.
- Use `dataclasses` `Optional[T]` to make absence visible in the type, not a magic `-1`/`""`.
- Pydantic / `attrs` validators belong at the boundary; once data is past the boundary, downstream code should not re-default.
