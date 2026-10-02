---
name: types-first
description: Type-driven domain modeling for Scala 3 — encode the invariant in the type system so the illegal state can't be represented (parse-don't-validate, distinct newtypes, closed enums, phantom/typestate). Use BEFORE writing a runtime check, validation guard, boolean flag, stringly-typed field, or primitive-typed domain value; defers to iron/scala/cats/cats-mtl-typed-errors for the concrete encodings. The compile-time sibling of fail-loud.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']

mechanical_checks:
  - id: types-first.prefer-enum-over-sealed
    tool: ripgrep
    severity: info
    pattern: '\bsealed\s+(?:trait|abstract\s+class)\b'
    paths: ['**/*.scala']
    message: 'Scala 3 — prefer `enum` for a closed ADT (enums express GADTs and variance too). Keep `sealed trait` only where an enum can''t reach: empty phantom/marker traits (no cases to enumerate) or a hierarchy whose leaves live in separate files (enum cases share one body).'
    references: ['types-first#closed-sets-are-enums-not-strings-or-booleans', 'types-first#phantom-types--typestate-for-protocols']
  - id: types-first.require-for-invariant
    tool: ripgrep
    severity: info
    pattern: '\brequire\s*\('
    paths: ['**/*.scala']
    paths_exclude: ['**/test/**', '**/it/**']
    message: 'Runtime `require` for a domain invariant — move the check to the boundary as a refined type / smart constructor so the type proves it ran and the core never re-checks (parse, don''t validate).'
    references: ['types-first#parse-dont-validate']
---

# Types-First — Make Illegal States Unrepresentable

## Quick start
- Before writing a runtime check, a `Boolean` flag, or a bare-primitive domain field, ask whether the **type** can make the illegal value impossible to construct.
- **Parse, don't validate** — a boundary check should *return a refined type*, not a `Boolean`/`Unit`. The type then proves the check ran; the core never re-validates.
- **Make illegal states unrepresentable** — model the domain so invalid combinations have no inhabitant: an `enum`/ADT instead of boolean soup, distinct newtypes instead of interchangeable primitives.
- **Reach for the encoding table** below — constraint → type feature → the skill that owns it. This skill carries the *decision*; the *vocabulary* lives in `iron`/`scala`/`cats`.
- **Right-size precision** — add a precise type when a wrong value passes unnoticed, is expensive, crosses a boundary, or is relied on far and wide; leave one-shot local values plain.
- The compile-time sibling of `fail-loud`: that skill surfaces failures through the *runtime* channel; this one removes the failure from the set of representable states.

## Why
Type-design debt is the quietest debt. A `String` status, a pair of `UUID` ids, a `Boolean` `isAdmin`, a `require` in a constructor — each compiles, passes its happy-path test, and ships. Then a typo'd status, a swapped id pair, or a forgotten flag combination becomes a production incident with no compile-time signal and no failing test. The fix lives upstream of the bug: model the domain so the illegal value can't be written down. A refined type, a closed `enum`, or a distinct newtype turns a class of runtime incident into a compile error the author sees immediately — and turns the code into something the next reader (or agent) cannot misuse by construction.

## The encoding decision table
The centre of this skill. When you catch yourself about to enforce a rule at runtime, find the row and reach for the encoding — then follow the owner skill for the syntax.

| Constraint you want to enforce | Encoding | Owner skill |
|---|---|---|
| Value within a range / format / non-blank | refined type (`Int :| Positive`, `String :| Not[Blank]`) | `iron` |
| Two values must never be swapped (ids, quantities) | distinct newtype (opaque or transparent) | `iron#domain-identifiers-are-refined-newtypes-never-bare-aliases` |
| A closed set of cases | `enum` / sealed ADT (never a `String`/`Boolean`) | `scala`, `iron#closed-set-refinement-types` |
| Either/or with no third option | union type `A \| B`, or a sealed ADT | `references/types-first.md` |
| Absence is possible | `Option`, never `null`/sentinel | `fail-loud`, `cats` |
| Only valid state transitions may compile | phantom types / typestate | `references/types-first.md` |
| A branch refines the type of a field | GADT | `references/types-first.md` |
| A capability threaded through many calls | `using`/`given`, or context function `?=>` | `scala#using-parameters`, `references/types-first.md` |
| Aggregate every validation error, not just the first | `ValidatedNec` | `cats`, `fail-loud#aggregate-validation-errors` |
| A domain failure that belongs in the signature | `Raise[F, E]` | `cats-mtl-typed-errors` |
| Forbid accidental `==` across unrelated types | multiversal equality / `CanEqual` (use `===`) | `scala#cats-suffix-syntax`, `references/types-first.md` |

Boolean-blindness (a `Boolean` parameter that should be a two-case `enum`) and primitive-obsessed signatures (several same-typed primitive params) are judgment calls the gate can't catch reliably — they live here, not in a mechanical rule. The deeper feature catalogue (phantom/typestate, GADTs, union & intersection, context functions, `CanEqual`, match types) is in `references/types-first.md`.

## Patterns

### 1. Parse, don't validate
A boundary check that returns `Unit`/`Boolean` throws the proof away — so every downstream caller re-checks, or worse, forgets to. Return a *refined type* once, at the edge, and thread it inward. The signature now proves the value is well-formed; the core can't re-validate because there's nothing left to check.
```scala
// Bad — validate returns nothing; `raw` stays a bare String, so the core must trust or re-check it
def validateEmail(raw: String): Unit =
  if !raw.contains('@') then throw IllegalArgumentException("bad email")

def send(raw: String): IO[Unit] =
  validateEmail(raw) *> mailer.send(raw)

// Good — parse once at the boundary; the refined type carries the proof inward
def send(email: Email): IO[Unit] = mailer.send(email)

val parsed: Either[String, Email] = Email.either(rawInput)   // refine at the edge — see iron
```
Cross-link `iron` (`.option`/`.either` are the parse entry points) and `fail-loud` (when the parse fails, surface it, don't default).

### 2. Closed sets are enums, not strings or booleans
A `String` status accepts every typo; a pair of `Boolean`s encodes four states when only three are legal. A closed `enum` admits exactly the legal cases and the compiler checks exhaustiveness at every `match`.
```scala
// Bad — four boolean combinations; `loading && error` and `done && data.isEmpty` are illegal yet representable
case class Fetch(isLoading: Boolean, hasError: Boolean, data: Option[Payload])

// Good — exactly the legal states; nothing else compiles, and every match is checked
enum Fetch:
  case Loading
  case Failed(reason: String)
  case Loaded(data: Payload)
```
For a closed set sourced at runtime (a status column read from the DB), keep the enumeration authoritative in one place — see `iron`'s closed-set refinement (`supported: List[T]`).

### 3. Distinct newtypes over interchangeable primitives
When two domain values share an underlying primitive, the compiler can't stop you passing them in the wrong order — the swap compiles and corrupts data at runtime. Distinct newtypes cost one line each and make the swap a compile error.
```scala
// Bad — both ids are UUID; grant(role, user) compiles and grants the wrong thing
def grant(user: UUID, role: UUID): IO[Unit]

// Good — distinct newtypes; grant(role, user) no longer compiles
def grant(user: UserId, role: RoleId): IO[Unit]
```
The encodings (`RefinedType` opaque vs `RefinedSubtype` transparent, `Pure` for identity-only ids) belong to `iron#domain-identifiers-are-refined-newtypes-never-bare-aliases`.

### 4. Phantom types / typestate for protocols
When an object has an order-of-operations contract — connect before send, open before write — encode the state as a phantom type parameter so an out-of-order call doesn't compile.
```scala
// Bad — nothing stops `send` on an unconnected socket; the contract is a comment and a runtime exception
class Socket:
  def connect(): Unit
  def send(payload: Payload): Unit

// Good — the state is a phantom parameter; `send` only resolves its evidence on a Connected socket
sealed trait Connected
sealed trait Disconnected

class Socket[State] private ():
  def connect(using State =:= Disconnected): Socket[Connected] = Socket.create
  def send(payload: Payload)(using State =:= Connected): Socket[Connected] = Socket.create

object Socket:
  def opened: Socket[Disconnected] = create
  private def create[S]: Socket[S]  = new Socket[S]
```
(The empty `sealed trait` markers carry no cases, so the prefer-enum rule — which is about closed ADTs — doesn't apply to them; there's nothing to enumerate.) Full treatment in `references/types-first.md`.

### 5. Union & intersection types for either-and-both
A stringly-typed error channel can't be handled exhaustively; a deep inheritance tree to share two capabilities is heavier than the ad-hoc requirement. Union types model "one of these"; intersection types model "all of these".
```scala
// Bad — the failure is a String; callers can't pattern-match the cases they must handle
def parse(raw: String): Either[String, Config]

// Good — a closed union of precise failures; each case is reachable and handled
def parse(raw: String): Either[ParseError | SchemaError, Config]

// Intersection — require both capabilities without a bespoke combined trait
def audit(entity: HasId & HasTimestamp): LogLine
```

### 6. Precision heuristics — when NOT to add a precise type
Precision has a cost: a newtype is a declaration, a `given Show`/`given Eq`, and a serialization codec. Spend it where a wrong value would do damage; skip it where the value is incidental.

| Add a precise type when the value… | Leave it plain when the value… |
|---|---|
| passes unnoticed if wrong (no runtime signal) | is obvious if wrong (a log line, a label) |
| is expensive if wrong (money, data loss, a destructive op) | is used once and locally |
| crosses a boundary (untrusted input, an API, storage) | is never branched on |
| is relied on far and wide across the codebase | is a one-shot intermediate inside a single expression |

```scala
// Over-engineered — a one-shot local heading nobody branches on or persists
val heading: NonEmptyString = NonEmptyString("Summary")

// Right-sized — the tenant id flows across boundaries and is branched on everywhere
val tenantId: Either[String, TenantId] = TenantId.either(header)
```

## Decision rule
When you reach for a runtime check (`require`, `if !valid then throw`), a `Boolean` flag, or a bare primitive for a domain value, ask in order:
1. **Can the type carry this invariant** so the illegal value never exists? → refined type / distinct newtype / `enum` (encoding table).
2. **Is this a boundary parse?** → return the refined type and thread it inward; don't re-validate downstream (pattern 1).
3. **Genuinely can't encode it** (depends on runtime data or external state)? → then surface it loudly through `Option`/`Either`/`Raise` — see `fail-loud` — don't bury it behind a default.

## Red flags — encode it, don't check it
| Excuse | Reality |
|---|---|
| "A runtime `require` is simpler" | It moves the failure from compile time to a production stack trace. A smart constructor is the same line count and the *type* proves the check ran. |
| "I'll validate at the top and trust it after" | That's parse-don't-validate — but only if the validated value carries a *refined type*. An unrefined `String` invites every downstream caller to re-check or forget. |
| "A `Boolean` flag is fine" | Two booleans encode four states; usually one or two are illegal. An `enum` encodes exactly the legal ones and the compiler checks every match. |
| "Both ids are just `UUID`s" | Then the compiler can't stop you passing them swapped — the bug is corrupted data, not a type error. Distinct newtypes cost one line each. |
| "It's only used once" | Then leave it plain — precision has a cost (pattern 6). Add the type when the value crosses a boundary or is branched on, not reflexively. |
| "`sealed trait` is what I always write" | In Scala 3 a closed ADT is an `enum` — and enums express GADTs and variance too. Keep `sealed trait` only where an enum can't reach: empty phantom markers (no cases) or leaves that must live in separate files. |
| "Strings serialize and log more easily" | A refined newtype serializes through one `given` and logs through `Show`; the convenience is a one-time cost, the safety is at every call site. |

## Cross-references
- `iron` — the concrete encodings for the "range/format" and "distinct identity" rows: refined types, opaque newtypes, `.option`/`.either` parse entry points.
- `scala` — `enum`/ADT syntax, `using`/`given`, `===`/`Eq`/`Show`.
- `cats` — `Validated`/`ValidatedNec` for accumulating errors, `Option` for absence.
- `cats-mtl-typed-errors` — `Raise[F, E]` when a domain failure belongs in the signature.
- `fail-loud` — the runtime sibling: when an invariant genuinely can't be encoded in a type, surface it loudly instead of defaulting it away.
- `reuse-first` — before hand-rolling a validator or newtype, check whether the refined type / combinator already exists.

## References
- Load `references/types-first.md` for the per-feature catalogue — opaque types, enums & ADTs, GADTs, union & intersection types, phantom/typestate, match types, context functions, multiversal equality / `CanEqual`, `inline`/`compiletime.error`, smart constructors — each with *what constraint it lets you express*, a snippet, and the gotchas.

---
*Source: the Scala type-design material in this skill is adapted, in project house style, from [`jpablo/vibe-types`](https://github.com/jpablo/vibe-types) (Apache-2.0). See the spine `README.md`.*
