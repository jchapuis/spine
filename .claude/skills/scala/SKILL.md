---
name: scala
description: Scala 3 coding conventions for this project. Use when writing, reviewing, or refactoring any Scala 3 code — covers syntax (braceless), naming, comments policy, functional style, Cats, using parameters, companion placement, durations, import order, compiler flags, and SBT. Testing conventions live in the `testing` skill.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']

mechanical_checks:
  - id: scala.no-final-case-class
    tool: ripgrep
    severity: error
    pattern: '^[^=;{}]*\bfinal\b[^=;{}]*\bcase\s+class\b'
    message: 'Drop `final` — Scala 3 case classes are effectively final'
    references: ['scala#case-classes-and-named-tuples']
  - id: scala.no-final-def
    tool: ripgrep
    severity: error
    pattern: '^[^=;{}]*\bfinal\b[^=;{}]*\bdef\b'
    message: 'Drop `final` on `def` — restructure the hierarchy or move the logic to a free function instead of locking down a method'
    references: ['scala#functional-style']
  - id: scala.no-final-class
    tool: ripgrep
    severity: error
    pattern: '\bfinal\s+class\b'
    paths: ['**/*.scala']
    message: 'No `final class` — the modifier is an OO reflex; to forbid extension, seal the hierarchy or use a case class/object'
    references: ['scala#functional-style']
  - id: scala.lambda-brace
    tool: ripgrep
    severity: error
    pattern: '\.\s*(map|flatMap|filter|foreach|fold[lL]eft|collect|traverse|mapN)\s*\{\s*\S'
    paths: ['**/*.scala']
    message: 'Use braceless lambda: .map: param =>'
    references: ['scala#syntax--braceless-scala-3-only']
  - id: scala.if-paren-brace
    tool: ripgrep
    severity: error
    pattern: '^\s*if\s*\([^)]+\)\s*\{'
    paths: ['**/*.scala']
    message: 'Use braceless: if cond then … else …'
    references: ['scala#syntax--braceless-scala-3-only']
  - id: scala.no-var
    tool: scalafix
    severity: warning
    rule: 'DisableSyntax.noVars'
    message: 'No `var` — use immutable values and functional state. Exception (frontend only): a cell nothing subscribes to, written inside an observer and read outside the signal graph — leave those alone'
    references: ['scala#functional-style', 'laminar#state-cells']
  - id: scala.no-tuple-positional
    tool: ripgrep
    severity: warning
    pattern: '\._[1-9][0-9]*\b'
    paths: ['**/*.scala']
    message: 'No tuple positional access — destructure with names or use named tuples'
    references: ['scala#case-classes-and-named-tuples']
  - id: scala.no-nowarn
    tool: ripgrep
    severity: error
    pattern: '@(annotation\.)?nowarn\b'
    paths: ['**/*.scala']
    message: 'Never `@nowarn` — fix the underlying warning'
    references: ['scala#compiler-flags']
  - id: scala.no-comments
    tool: scalafix
    severity: warning
    rule: 'NoComments'
    message: 'No code comments — name things expressively. Scaladoc, block, and line comments are all flagged; only scala-cli directives (`//>`) are allowed'
    references: ['scala#comments']
  - id: scala.cats-syntax-all-import
    tool: ripgrep
    severity: error
    pattern: '^import\s+cats\.syntax\.all\b'
    paths: ['**/*.scala']
    message: 'Import specific `cats.syntax.<module>.*` instead of `cats.syntax.all.*` — keeps -Wunused honest'
    references: ['scala#import-order-cats--iron']
  - id: scala.default-companion-name
    tool: ripgrep
    severity: warning
    pattern: '^\s*(val|def)\s+Default\s*[:=]'
    paths: ['**/*.scala']
    message: 'Avoid `.Default` companion vals — values should be explicit at the call site'
    references: ['scala#case-classes-and-named-tuples']
  - id: scala.no-return
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noReturns'
    message: '`return` in Scala 3 has surprising semantics inside lambdas — restructure the expression instead'
    references: ['scala#functional-style']
  - id: scala.no-while
    tool: scalafix
    severity: error
    rule: 'DisableSyntax.noWhileLoops'
    message: 'No `while` loops — they require `var`. Use recursion, fold, traverse, or fs2 Stream'
    references: ['scala#functional-style']
  - id: scala.scalafmt
    tool: scalafmt
    severity: error
    config: '.scalafmt.conf'
    message: 'Run scalafmtAll'
  - id: scala.private-val-type-ascription
    tool: ripgrep
    severity: warning
    pattern: '^\s*(?:private|protected)(?:\[[^\]]+\])?\s+(?:\w+\s+)*val\s+\w+\s*:\s*\S'
    paths: ['**/*.scala']
    message: 'Drop the type ascription on this private/protected val — inference handles it. Exception: inference picks an over-narrow type that breaks downstream (e.g. an Iron-refined key in a Map that the call site needs widened to Int). Rename the val if you want self-documentation.'
    references: ['scala#type-ascription-restraint']
  - id: scala.private-def-return-type
    tool: ripgrep
    severity: warning
    multiline: true
    pattern: '^[ \t]*(?:private|protected)(?:\[[^\]]+\])?[ \t]+(?:\w+[ \t]+)*def[ \t]+\w+(?:\[[^\]]*\])?[ \t]*\((?:[^()]|\([^()]*\))*?\)(?:[ \t]*\((?:[^()]|\([^()]*\))*?\))?[ \t]*:[ \t]*[^=\n]+='
    paths: ['**/*.scala']
    message: 'Drop the return-type ascription on this private/protected def — inference handles it. Exception: recursive defs (compiler requires it). Rename the def for clarity instead of ascribing.'
    references: ['scala#type-ascription-restraint']
  - id: scala.no-local-type-ascription
    tool: scalafix
    severity: warning
    rule: 'NoLocalTypeAscription'
    message: 'Drop the type ascription on this local val/var/def — inference handles it. Ascription is for public API only (or recursive defs, where the compiler requires it).'
    references: ['scala#type-ascription-restraint']
  - id: scala.no-qualified-private
    tool: ripgrep
    severity: error
    pattern: '\b(?:private|protected)\s*\[\s*(?!this\b)\w+\s*\]'
    paths: ['**/*.scala']
    message: 'No qualified-private — `private[pkg]` / `protected[pkg]` parks helpers in a soft-private namespace. Use plain `private` (file/class scope), or move the code into a properly-named sub-package and use vanilla visibility within it.'
    references: ['scala#no-qualified-private-namespaces']
---

# Scala 3 — Project Conventions

## Quick start
- Braceless syntax everywhere — no `{` braces in control structures, class bodies, lambdas, or pattern matches.
- **No abbreviations in names** — ever. Write `averageLatency` not `avgLatency`, `requestHeaders` not `reqHeaders`, `respond` not `k`. Single-letter lambda params are forbidden (except `_` for unused).
- No `var`, no mutable state, no `foreach` + accumulator — use `map`/`foldLeft`/`traverse`. One exception, browser-only: `laminar#state-cells`.
- No code comments — name things expressively instead.
- **No type ascription on private members or local `val`s** when inference is unambiguous. Type ascription is for public API, where it doubles as documentation.
- **Prefer Scala 3 named tuples** for ad-hoc records — `(name = ..., value = ...)` over anonymous tuples or one-off case classes.
- **One file per top-level class** — filename matches the principal type; split files that contain multiple top-level classes / case classes.
- All warnings are errors (`-Werror -Wunused:all`) — every unused import, param, or local is a compile failure.

## Key rules

### Syntax — braceless Scala 3 only
```scala
// Bad
if (condition) { doA() } else { doB() }
list.map { item => item.name }

// Good
if condition then doA() else doB()
list.map: item =>
  item.name
```
Lambda parameter syntax: `: param =>` (colon-space), never `{ param =>`.

---

### Naming
- **No abbreviations** — in lambdas, params, local variables: `location =>` not `l =>`, `role =>` not `r =>`. Exception: `_` for genuinely unused params.
- **Named parameters at call sites** when multiple args share the same type:
```scala
rectangle(left = startX, top = startY, right = endX, bottom = endY)
```
- **Conversely, omit the parameter name when the types already disambiguate** and the name only echoes the binding — `compute(threshold = threshold, label = label)` becomes `compute(threshold, label)` when a `Double` and a `String` can't be confused.

---

### Functional style
- No `var`, no mutable collections. Use `foldLeft`, `map`, `flatMap`, `traverse`.
- Use `Try()` over try/catch for Java interop.

**The one `var` exception** is a frontend cell nothing subscribes to, written inside an observer and read outside the signal graph. `laminar#state-cells` owns it — which cell a read belongs to, why `Var` cannot serve, and why a one-slot array or a cell wrapper is the same mutation wearing a costume. It stops at the browser: server code has no reader outside a signal graph, so `scala.no-var` there is a finding to fix, not a warning to leave.

---

### Comments
**Default: zero comments.** Naming is the documentation for code; tests are the documentation for behavior. The `scala.no-comments` Scalafix rule is AST-aware and flags *every* comment token — line, block, and Scaladoc alike — leaving only scala-cli directives (`//>`) permitted. The kinds below are why:

- **What-comments** — restating what the next line does. Rename or restructure instead; needing a comment is proof the name failed.
- **History comments** — "no longer", "previously", "moved from", "used to". Git holds the history; a comment holding it goes stale and misleads.
- **Dated or measured values** — a date, a benchmark number, an accuracy figure baked into a comment is outdated the next run.
- **Config values in comments or docs** — quoting a default or threshold in prose drifts silently when the config changes (see `refactor-completeness`).
- **Scaladoc** — most of it restates what the signature already says. A doc line that says what the types say is noise; if it says something the types can't, it belongs in the README.
- **Comments describing code that doesn't exist** — placeholders like "soon filled with X" or workflow comments for steps that were never added.

Where explanations belong instead:
- **Prose explanations go in the global README** — not inline, and not a standalone document per topic.
- **Tests document behavior** — a well-named test case explains an edge case better than a comment above the branch.

Sanctioned exceptions (rare, deliberate):
- **How a config value was determined** — a tuning constant may carry one comment explaining its derivation, since the derivation isn't recoverable from code.
- **A genuinely surprising why** — a non-obvious external constraint (an API quirk, a platform bug workaround). If the reader would ask "why on earth", one comment earns its place.

---

### Type ascription restraint
**Default: no ascription.** Type ascription belongs on public API only — there it doubles as documentation. Everywhere else (private/protected members, local `val`s, local `def`s) drop it. Inference is what you want; the type adds noise and tends to drift out of date.

```scala
// Bad — private val: type adds noise
private val cachedResults: Map[Key, ResultSet] = computeResults()

// Good — let inference do the work
private val cachedResults = computeResults()

// Bad — private def: return type adds noise
private def computeTotal(items: List[Order]): Money = items.map(_.total).combineAll

// Good — let inference do the work; rename if you want clarity
private def totalOf(items: List[Order]) = items.map(_.total).combineAll

// Bad — local val with type ascription
def render(state: State): HtmlElement =
  val title: String = state.user.profile.displayName.getOrElse("Guest")
  div(title)

// Good — local val, type inferred
def render(state: State): HtmlElement =
  val title = state.user.profile.displayName.getOrElse("Guest")
  div(title)
```

Legitimate exceptions — narrow:
- Public method signatures (return type and parameters).
- Recursive `def`s (the compiler requires it).
- Inference picks an over-narrow type that breaks downstream — verify by removing the ascription and reading the compiler error before adding it back.

If you're tempted to ascribe "for clarity", **rename instead** — `totalOf(items)` reads better than `computeTotal(items): Money`.

---

### File-per-class convention
Filename matches the principal top-level type. Split files that contain multiple top-level classes / case classes — each gets its own `.scala` file. Sealed ADT cases stay in the same file as the sealed parent; small companion objects stay below their class.

**God-file rule.** When a file grows past a few hundred lines, count its concerns — distinct responsibilities a reader could name (rendering + state + parsing + IO wiring…). More than one concern → split by concern, each into its own file or sub-package, before the PR: pure logic into helpers or an algebra (`server-architecture`), component sections via the class-scope refactor (`#class-scope-for-helper-bundles`), related helper clusters into a domain-named sub-package (`#cohesive-sub-packages`). "It grew organically" is how every god file happened; the moment you notice is the moment to split.

---

### Cats suffix syntax
```scala
someValue.some      // not Some(someValue)
result.asRight      // not Right(result)
error.asLeft        // not Left(error)
opt.toRight(error)
```
Use `===` / `=!=` (Cats `Eq`) instead of `==` / `!=` for domain types. Provide `given Eq` and `given Show` on enums and case classes.

---

### `using` parameters — extended currying and naming discipline

**Name a `using` param only when it is referenced by name in the body. Anonymous if forwarded only.** Use **separate `(using …)` clauses** so each parameter can be named or anonymous independently — collapsing them into a single clause forces all-named or all-anonymous.

```scala
// Bad — single clause forces both to be named even though one is only forwarded
def handle()(using service: OrderService, validator: TokenValidator) = ...

// Good — extended currying; named where used, anonymous where forwarded
def handle()(using service: OrderService)(using TokenValidator) =
  service.placeOrder(...)  // uses `service` by name; TokenValidator is forwarded implicitly to a downstream call
```

**Promote ambient context to `using` consistently across the codebase.** When a context value (tenant, locale, session, request context) is needed by many methods, model it as a `given` once at the boundary and propagate it via `using` everywhere downstream. Do **not** mix `summon[UserSession]` calls with `using` parameters — pick one (`using`) and apply it consistently.

```scala
// Bad — ad-hoc summon scattered through the codebase
def renderHeader(): Html =
  val session = summon[UserSession]
  ...

// Good — `using` propagates the context; the body references it by name only when needed
def renderHeader()(using session: UserSession): Html = ...

// And further downstream, anonymous forwarding when the body doesn't reference it directly
def renderPage(sections: List[Section])(using UserSession): Html =
  sections.map(renderSection).combineAll  // UserSession forwards implicitly
```

This is one of Scala 3's structural advantages — use it as a project-wide convention, not a one-off optimization.

---

### Companion object placement
Companions go **below** their class, not above. A narrow error or detail type used by only one class belongs in **that class's companion**, not floating in a shared domain package — see `server-architecture`.

---

### Class scope for helper bundles

When an `object` — top-level or companion — accumulates three or more `private def` helpers that thread the same parameters, lift them into a plain `class` (not a `case class`) whose constructor captures those parameters. Make the constructor `private` and expose construction via `def apply(...)` on the object — external call sites stay unchanged, but the helpers now read shared inputs from class scope instead of taking them as arguments.

- Eliminates parameter-passing noise across the helper bundle.
- Each helper is independently readable because shared inputs are named once, in the constructor.

Don't apply when the object is a pure stateless utility (no shared parameters to capture), when only one or two helpers exist (not enough threading to justify the structure), or when only a subset of the helpers share the parameters and the others would have to be wedged in artificially.

```scala
// Bad — three private defs thread (state, config) across the bundle
object Widget:
  def apply(state: Var[Int], config: Config): HtmlElement =
    div(header(state, config), body(state, config), footer(state, config))

  private def header(state: Var[Int], config: Config) = ...
  private def body(state: Var[Int], config: Config) = ...
  private def footer(state: Var[Int], config: Config) = ...

// Good — shared parameters live in class scope; companion `apply` keeps the call site clean
class Widget private (state: Var[Int], config: Config):
  def render: HtmlElement = div(header, body, footer)
  private def header = ...
  private def body = ...
  private def footer = ...

object Widget:
  def apply(state: Var[Int], config: Config): HtmlElement =
    new Widget(state, config).render
```

The pattern shows up most often in two places: Laminar components whose helper `def`s on the surrounding `object` all take the same `Var`s / `using` context (see `laminar#component-structure`), and stateful server algebras whose private helpers all read the same injected config or dependency (see `server-architecture#algebras`).

---

### No qualified-private namespaces

Never use `private[<package-or-class>]` or `protected[<package-or-class>]`. They're a soft-private workaround that lets helpers leak across every file in a package while pretending to be encapsulated — the visibility qualifier says "I'm hiding this" but the package qualifier silently grants every sibling file the same access plain `public` would.

Two real alternatives:
- **One consumer** — make the helper plain `private` and put it on that class (or its companion — see "Companion object placement").
- **Multiple consumers** — move the code into a properly-named internal sub-package (`pkg.internal`, `pkg.detail`) and use vanilla `private`/public visibility *within* that sub-package. The sub-package name documents the intent; `private[pkg]` does not.

```scala
// Bad — `share` leaks to every file in `mypkg`
private[mypkg] def share(x: Int): Int = x * 2

// Good — plain private on the class that uses it
class Worker:
  private def share(x: Int): Int = x * 2
  def run(x: Int) = share(x)
```

Exception: `private[this]` is a different construct (instance-level access in Scala 2; mostly redundant in Scala 3) and is out of scope for this rule. The mechanical check leaves it alone.

References: `server-architecture#scope-error-and-detail-types` for the broader scoping principle.

---

### Cohesive sub-packages

When a cluster of related helpers or an algorithm family grows past a couple of loose files — geometry helpers, a wind/particle simulation, a scoring pipeline — group it into a domain-named sub-package (`geometry`, `wind`, `scoring`) and give it its own test suite. Don't leave the cluster as scattered files sitting beside unrelated code in a flat package.

- The package name documents what the cluster *is*, the way `pkg.internal` documents "implementation detail" in the qualified-private case above.
- A cohesive cluster is a testable unit — extracting it is the moment to add the tests it was missing.
- This is the positive form of the same instinct as "No qualified-private namespaces": organise by named package, not by visibility tricks.

See `factor-out#where-extracted-code-goes` for the placement table.

---

### Extension Ops objects
Shared collection operations on a domain type go in a dedicated `object XxxOps` with `extension` methods:
- `Ops` suffix = domain-tied collection extensions (not generic utilities).
- Import explicitly at use sites: `import OrderLineOps.*`.
- Use `===` (Cats `Eq`) inside extension methods.
- Extend the **collection type**, not the element type.
- One `XxxOps.scala` file per object.

---

### Tuples
- No positional access — never `._1`, `._2`.
- **Prefer Scala 3 named tuples** for ad-hoc records over anonymous tuples or one-off case classes.
- Destructure with names or use Scala 3 named tuples:
```scala
// Bad
.sortBy(_._3)
// Good
.sortBy((_, _, totalPercent) => totalPercent)
// Good — named tuple
type Cell = (row: Int, col: Int)

// Prefer named-tuple literals at construction:
val origin = (row = 0, col = 0)
```

---

### Case classes
- **Group fields by shared semantics** — nest cohesive sub-records rather than dumping every field into one flat bag. A `Settings` with a dozen unrelated fields usually wants nested `Display`, `LastSelection`, … groups.
- **Group semantically-linked parameters into a type** — two `Double`s that always travel together (a coordinate pair, a range, a bound pair) become a small named type or named tuple, not parallel primitives.
- **`Option` means domain-absent, never a flow stage.** A field that is `None` in one phase and `Some` in another is smuggling a state machine into a product type — model the flow as separate types (or an enum of stages), so each stage's fields are total.
```scala
// Bad — Option encodes "not yet connected"; every consumer must re-derive the stage
case class Account(userId: UserId, providerToken: Option[Token], providerAthleteId: Option[AthleteId])

// Good — the flow is in the types; each stage is total
case class Account(userId: UserId)
case class ConnectedAccount(userId: UserId, providerToken: Token, providerAthleteId: AthleteId)
```
- **No `final case class`** — Scala 3 case classes are effectively final; the modifier is noise.
- **No `.Default` companion constant** when parameter defaults already give the natural zero value — `Foo()` *is* the default.
- **Closed sets are `enum`s, not `String`/`Boolean`** — model a fixed set of cases as a Scala 3 `enum`; reserve a hand-written `sealed trait` for GADTs / type-parameter refinement an enum can't express. See `types-first`.

---

### Durations
- **`FiniteDuration` + duration literals** (`30.seconds`, `2.hours` via `scala.concurrent.duration.*`) — never raw numeric seconds/millis in signatures, fields, or constants.
- **No hand-rolled unit-conversion constants** — a `secondsPerHour = 3600` val is the tell that a `FiniteDuration` is missing upstream.
- **Convert at boundaries only** — `.toUnit`/`.toMillis`/`.toJava` appear once, at the API or platform boundary that demands the raw number; if a conversion recurs, wrap it in an extension.

---

### Iron refined types
- **Opaque newtypes** for domain-specific values (`Temperature`, `Price`, `UserId`) — including entity ids that are "just a `UUID`". A bare `type OrderId = UUID` alias lets an order id slot into a parameter expecting a different id (both are `UUID`, so the swap compiles); a refined newtype (`RefinedSubtype[UUID, Pure]`) makes the compiler reject it. See `iron#domain-identifiers-are-refined-newtypes-never-bare-aliases`.
- **Type aliases** only for generic reusable constraints (`PosInt`, `NonEmptyString`).
- No redundant conversions on transparent subtypes — don't call `.value`/`.toDouble`/`.toInt` unless crossing underlying types in arithmetic.

---

### Import order (Cats + Iron)
Order is load-bearing — Iron's wildcard shadows same-named packages:
```scala
import cats.data.{NonEmptySet, NonEmptyList}   // Cats FIRST
import io.github.iltotore.iron.*                // Iron wildcard
import io.github.iltotore.iron.cats.given       // Iron-Cats integration LAST

// For upickle + iron:
import upickle.default.*                        // upickle BEFORE iron wildcard
import io.github.iltotore.iron.*
import io.github.iltotore.iron.upickle.given
```

Import the **specific** `cats.syntax.<module>.*` modules you use (`cats.syntax.eq.*`, `cats.syntax.option.*`, `cats.syntax.traverse.*`, …), not the `cats.syntax.all.*` wildcard — see `cats`.

---

### Compiler flags
Projects compile with `-Werror` and `-Wunused:all`. Consequences:
- **Never use `@annotation.nowarn`** — fix the issue instead.
- Every unused import, local, param, private, or implicit is a compile error.

---

### Testing
Testing conventions live in the dedicated `testing` skill — MUnit + CatsEffectSuite, property-based testing, generator reuse, `sampleSome` fixtures, static expectations, mock hygiene, integration-test discipline.

---

### Metals MCP tools
When Metals MCP tools are available, prefer them over equivalent `sbt` CLI commands — they are faster and return more focused output. Fall back to `sbt` only when no Metals tool covers the task.

Format for commits (CLI only): `sbt scalafmtAll` before committing; CI enforces `sbt scalafmtCheckAll`.

---

### Pull requests
- Concise bullet-point summary + a "Fixes" line.
- No "Test plan" section, no checklists, no migration notes.

## Related skills
- Testing conventions (MUnit, ScalaCheck properties, generators, sampleSome, mocks) → `testing`
- Cats core combinators (Functor/Applicative/Traverse/Foldable/MonadError/Validated/OptionT/EitherT) → `cats`
- Cats Effect IO patterns → `cats-effect-io`
- Typed errors (Raise/Handle) → `cats-mtl-typed-errors`
- Iron refined types (detailed) → `iron`
- Type-driven design (make illegal states unrepresentable, parse-don't-validate, encoding table) → `types-first`
- Pre-coding: search before writing helpers → `reuse-first`
- Anti-duplication pre-PR pass → `factor-out`
- Pre-PR audit (diff-audit, schema parity, missing tests) → `refactor-completeness`
- Surface failures, no silent fallbacks → `fail-loud`