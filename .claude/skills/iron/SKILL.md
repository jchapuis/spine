---
name: iron
description: Iron refined types and opaque newtypes for Scala 3. Use when defining domain value types, attaching compile-time/runtime constraints to primitives, choosing between opaque newtypes and type aliases, integrating Iron with Skunk, Circe, or Tapir — or whenever a signature is about to carry a raw String/Int/Double/Long that has domain meaning (a unit, a range, a format, an identity).

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']

mechanical_checks:
  - id: iron.no-require
    tool: ripgrep
    severity: error
    pattern: '(?<![.\w])require\s*\('
    paths: ['**/*.scala']
    message: '`require` is forbidden: it throws at runtime — encode the invariant as an Iron refined type (compile-time for literals, `.option`/`.either` at boundaries), or return a case of the module''s typed error ADT when real data can break it'
    references: ['iron#when-to-reach-for-iron-over-bespoke-validation']
  - id: iron.no-apply-unsafe
    tool: ripgrep
    severity: error
    pattern: '\.applyUnsafe\s*\('
    files_must_import: ['io\.github\.iltotore\.iron']
    paths_exclude: ['**/src/test/**/*Generators.scala', '**/src/it/**/*Generators.scala']
    message: 'No `.applyUnsafe` — use the type constructor `T(...)` for literals or `.option`/`.either` for runtime values'
    references: ['iron#dont-do-this']
  - id: iron.no-refine-unsafe
    tool: ripgrep
    severity: error
    pattern: '\.refineUnsafe\s*[\(\[]'
    files_must_import: ['io\.github\.iltotore\.iron']
    paths_exclude: ['**/src/test/**/*Generators.scala', '**/src/it/**/*Generators.scala']
    message: 'No `.refineUnsafe` — use `.refineEither`/`.refineOption` at I/O boundaries'
    references: ['iron#dont-do-this']
  - id: iron.assume-on-literal-string
    tool: ripgrep
    severity: warning
    pattern: '"[^"]+"\.assume\['
    files_must_import: ['io\.github\.iltotore\.iron']
    message: 'No `.assume` on a string literal — call the type constructor `T("...")` so the macro validates at compile time'
    references: ['iron#dont-do-this']
  - id: iron.bare-id-alias
    tool: ripgrep
    severity: warning
    pattern: '^\s*type\s+\w*(?:Id|ID)\s*=\s*(?:UUID|String|Long|Int|java\.util\.UUID)\b'
    paths: ['**/*.scala']
    message: 'Domain id aliased to a bare primitive — define a refined newtype (`object FooId extends RefinedSubtype[UUID, Pure]`) so one id type can''t silently substitute for another.'
    references: ['iron#domain-identifiers-are-refined-newtypes-never-bare-aliases']
---

# Iron — Refined Types (Scala 3)

## Quick start
- **A value with a unit, range, format, or identity deserves a refined type.** Before typing a raw `Double`/`Int`/`String`/`Long` field or parameter, check the project's existing refined types and reuse one; if none fits, define one. Raw primitives forfeit range assumptions downstream and let unrelated values interchange. (Time durations are the exception — they get `FiniteDuration`, see `scala#durations`.)
- Use `RefinedType` for opaque newtypes (domain semantics, prevents value mixing).
- Use `RefinedSubtype` for transparent subtypes — the value IS-A `Base`, passes through to APIs expecting the base type without `.value`. See `## RefinedSubtype recipe` below.
- Use `type Alias = Base :| Constraint` for generic/shared constraints without newtype semantics.
- Import `io.github.iltotore.iron.*` and `io.github.iltotore.iron.constraint.all.*`.
- For library integrations, import the matching `given` instances (`iron.cats.given`, `iron.skunk.given`, `iron.circe.given`).
- Use `refineEither` for runtime validation of external values; `refineUnsafe` only for values you control at the call site.
- For **literal** values, use `apply` directly (e.g. `Email("user@example.com")`) — the macro validates at compile time. Never use `assume` for literals.
- `assume` is only for runtime values already known to satisfy the constraint (e.g. values read back from a trusted store).
- Read `references/iron.md` for constraint catalogue, integration examples, and patterns.

## Workflow
1. Identify the primitive base type (`String`, `Int`, `Double`, …).
2. Choose the constraint: single (`Positive`), composed (`Not[Blank] & MaxLength[64]`), or custom.
3. Decide on opaque newtype vs. type alias based on domain requirements (see rules below).
4. Add integration imports for libraries in use (Skunk, Circe/Upickle, Tapir, Cats).
5. Validate external values at system boundaries with `refineEither`; rely on compile-time for literals.

The `refineUnsafe` / `applyUnsafe` bans target I/O boundaries, where the value is unknown until runtime. Inside a ScalaCheck generator the range is proven by construction (`Gen.choose(-30.0, 0.0).map(_.refineUnsafe[LessEqual[0.0]])`), so `refineEither` would only add a dead error branch — the gate excludes `**/src/test/**/*Generators.scala` from both rules for that reason.

## When to reach for Iron over bespoke validation
If you find yourself writing a non-blank, non-empty, range, length, regex, or format check by hand, stop — Iron almost certainly already carries the constraint as a type, and the codebase likely has the right newtype defined.

```scala
// Bad — bespoke non-blank validation
val cleaned: Option[String] =
  Option(input).map(_.trim).filter(_.nonEmpty)

// Bad — re-implementing what Iron's .option already gives you
def attributeValue(raw: String): Option[OrderId] =
  Option(raw).filter(_.nonEmpty).map(OrderId.applyUnsafe)

// Good — Iron-refined newtype carries the constraint, .option / .either
//        give you the validation entry points for free
val orderId: Option[OrderId]              = OrderId.option(input)
val orderId: Either[String, OrderId]      = OrderId.either(input)
```

Common shortcuts:
- `T.option(raw)` → `Option[T]` (returns `None` on constraint failure)
- `T.either(raw)` → `Either[String, T]` (carries the constraint message)
- `T.applyUnsafe(raw)` → `T` (throws on misuse — only when the source is trusted; cross-link `fail-loud`)
- `T.assume(raw)` → `T` (no runtime check; only when an upstream invariant has already proven the constraint)

When you have a list of required fields plus their refiners, **pair them in one list and traverse** rather than calling each refiner inline. One source of truth for "what's required and how to refine it":

```scala
val required: List[(String, String => Either[String, ?])] =
  List(
    "order_id" -> OrderId.either,
    "amount"   -> Amount.either
  )

required.traverse((key, refine) => refine(props(key)).toValidatedNec)
```

If a refined type doesn't yet exist for the field, define one — that's almost always cheaper than threading bespoke validation through the call site, and it makes the constraint a part of every signature that mentions the field.

### Parse, don't validate — thread the refined type inward
`refineEither` / `.either` at a boundary returns the *refined type*, not a `Boolean` or `Unit` — so
the proof that the check ran travels with the value. Pass that type inward and the core never
re-validates: a function deep in the domain should take `Email`, not `String` plus a re-check. The
refinement is the single parse point; everything downstream trusts the type. See
`types-first#parse-dont-validate`.

### Don't re-implement Iron's standard combinators
If you find yourself writing a custom `extension` method that mirrors `assume` / `option` / `either` / `applyUnsafe`, delete it. Those are part of the standard Iron API on every refined companion. Re-implementing them locally means future readers won't recognize the project's "real" Iron syntax.

### No `.get` in tests; lean on `autoRefine` for compile-time literals
For a refined value type like `Pos0Int`, write `(n: Pos0Int)` (with `import io.github.iltotore.iron.autoRefine`) rather than `Pos0Int(n).get`. Iron value literals resolve at compile time via the macro — `.get` is a smell that suggests the value isn't actually a literal, in which case it should go through `.option` / `.either` and surface the failure.

### Closed-set refinement types — single source of truth
When a refinement's constraint is a **closed set** — a regex alternation of literal strings, an `In[...]` constraint, a bounded int set — don't leave the enumerated list implicit in the regex. Expose it explicitly as `supported: List[T]` on the companion. Anything downstream that needs the list (UI dropdowns, ScalaCheck Arbitraries, `Gen.oneOf`, `for/yield` over the set) derives from `supported` rather than restating the literals.

```scala
type SupportedLanguage = Match["^(en|fr|de)$"]
type Language          = Language.T

object Language extends RefinedType[String, SupportedLanguage]:
  val supported: List[Language] = List("en", "fr", "de").map(Language.assume)

// Downstream — no restated literals, nothing can drift
val dropdownOptions: List[Language] = Language.supported
given Arbitrary[Language]           = Arbitrary(Gen.oneOf(Language.supported))
```

The opposite shape — regex allows `en|fr|de|es|it` but the app only ships translations for `en|fr|de` — is a silent dead-branch bug: the dropdown offers languages the rest of the system can't honor. Keeping the regex and `supported` adjacent makes the closed set authoritative in one place.

## Newtype vs. type alias rules
- **Opaque newtype** — use when the value represents a distinct domain concept that must not be substituted for another type with the same underlying representation:
  ```scala
  type UserId = UserId.T
  object UserId extends RefinedType[String, Not[Blank]]
  ```
- **Type alias** — use when the constraint is a reusable invariant shared across multiple places without distinct identity:
  ```scala
  type NonBlankString = String :| Not[Blank]
  ```
- Never use a type alias where an opaque newtype is needed: a `UserId` and an `Email` both being `String :| Not[Blank]` must not be interchangeable.

### Domain identifiers are refined newtypes, never bare aliases
An entity id that is "just a `UUID`" (or `String`/`Long`) still deserves a refined newtype. A bare `type OrderId = UUID` alias is structurally identical to every other `UUID` alias, so the compiler happily lets a survey id slot into a parameter expecting a scraping-input id — the swap compiles clean and only surfaces as a runtime mix-up.

```scala
// Bad — two ids over the same primitive interchange silently
type OrderId = UUID
type InputId  = UUID
def resolve(order: OrderId, input: InputId): Result = ...
resolve(input, survey)   // compiles — both are UUID

// Good — refined newtypes; the compiler rejects a swapped id
type OrderId = OrderId.T
object OrderId extends RefinedSubtype[UUID, Pure]
type InputId = InputId.T
object InputId extends RefinedSubtype[UUID, Pure]
```

`Pure` (from `io.github.iltotore.iron.constraint.all.*`) is the no-op constraint — reach for it when the id carries no validation beyond its base type; the value is doing identity work, and identity is what the newtype protects. `RefinedSubtype` keeps the value assignable to its base `UUID` at DB / serialization boundaries while still stopping one id type from substituting for another; use `RefinedType` instead when you want the id fully opaque (must be unwrapped). See `## RefinedSubtype recipe`.

## RefinedSubtype recipe

Use `RefinedSubtype` when the domain type IS-A subtype of its base (e.g. `Email` is-a `String`), so it can be passed anywhere the base type is accepted without explicit conversion. Use `RefinedType` (opaque) when you want strict type boundaries — the value must be explicitly unwrapped.

```scala
// 1. Name the constraint (optional, but useful for error messages)
type ValidEmail = Match["^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"] DescribedAs "Must be a valid email address"

// 2. Declare the transparent alias
type Email = Email.T

// 3. Companion extends RefinedSubtype
object Email extends RefinedSubtype[String, ValidEmail]:
  given Eq[Email]     = Eq.fromUniversalEquals      // always
  given Show[Email]   = Show.show(_.value)           // always
  given Schema[Email] = Schema.string                // Tapir; swap for ReadWriter / Codec etc.
```

The integration `given` varies by use context:
- **Tapir**: `Schema[T] = Schema.string`
- **Upickle**: `ReadWriter[T] = summon[ReadWriter[String]].bimap(_.value, T.applyUnsafe)`
- **Skunk**: `Codec[T]` via `iron.skunk.given` import

`Eq` and `Show` are always present. Add `Order[T]` only when the type appears in sorted collections.

## Output expectations
- Every domain primitive with invariants should be an opaque newtype or refined type alias.
- Refinement should fail at compile time for known literals; runtime refinement at I/O boundaries.
- Integration codecs should be derived via `given` instances — no hand-written boilerplate.

## References
- Import order: see `scala` skill (Iron wildcard must come after other library imports).
- Load `references/iron.md` for constraint catalogue, code samples, and integration patterns.
