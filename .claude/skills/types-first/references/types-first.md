# Types-First — Feature Catalogue

The Scala 3 type-system features behind the encoding decision table in `SKILL.md`, one short
section each: **what constraint it lets you express**, a minimal snippet, the gotchas, and the
skill that owns the day-to-day vocabulary. Reach here from a row in the decision table when you
need the mechanics. Adapted to house style from `jpablo/vibe-types` (Apache-2.0).

A through-line worth holding: *every parse, check, or branch upgrades information — capture that
information in a type at the boundary and thread it inward, so the core never re-derives it.*

---

## Opaque types
**Encodes:** a distinct type that shares its representation at runtime but is unrelated at compile
time — zero-cost, non-substitutable.
```scala
object Geometry:
  opaque type Radians = Double
  object Radians:
    def apply(value: Double): Radians = value
  extension (angle: Radians) def value: Double = angle
```
- Transparent *inside* the defining scope, abstract *outside* — the boundary is the enclosing
  object/class, not the file.
- Cannot be local, cannot be `private`, single type-parameter list only.
- `==` translates to the underlying type's equality — pair with `CanEqual` (below) if you want to
  forbid cross-type comparison.
- **Defer to `iron`** for the refined flavour: `RefinedType`/`RefinedSubtype` give you the opaque
  newtype *plus* a constraint and the `.option`/`.either` parse entry points in one declaration.
  Reach for a bare `opaque type` only when there is genuinely no constraint and no Iron in scope.

## Enums & ADTs
**Encodes:** a closed set of cases, with compiler-checked exhaustive matching.
```scala
enum Shape:
  case Circle(radius: Double)
  case Rectangle(width: Double, height: Double)

def area(shape: Shape): Double = shape match
  case Shape.Circle(radius)         => math.Pi * radius * radius
  case Shape.Rectangle(width, high) => width * high
```
- Scala 3 `enum` cases may carry different fields and type parameters — far more than a Java enum.
- A non-exhaustive `match` is a `-Werror` failure here, so the closed set is enforced at every use.
- `enum` desugars to a `sealed` hierarchy; prefer it over a hand-written `sealed trait` + `case
  class` hierarchy unless the leaves must live in separate files (enum cases share one body) or
  there are no cases to enumerate (phantom markers, below).
- **Defer to `scala`** for syntax conventions; **`iron#closed-set-refinement-types`** when the set
  is a refinement over a primitive (a status parsed from a string).

## GADTs (generalized algebraic data types)
**Encodes:** a per-branch refinement of a type parameter — matching a case *teaches the compiler*
the concrete type, so the result type is exact without a cast.
```scala
enum Expr[A]:
  case IntLit(value: Int)         extends Expr[Int]
  case BoolLit(value: Boolean)    extends Expr[Boolean]
  case If(cond: Expr[Boolean], thenBranch: Expr[A], elseBranch: Expr[A]) extends Expr[A]

def eval[A](expr: Expr[A]): A = expr match
  case Expr.IntLit(value)  => value          // here A =:= Int
  case Expr.BoolLit(value) => value          // here A =:= Boolean
  case Expr.If(cond, t, e) => if eval(cond) then eval(t) else eval(e)
```
- Scala 3 `enum`s express GADTs directly — `Expr` above is an `enum`, so a GADT needs no
  hand-written `sealed trait`. The leverage is the type-parameter refinement: matching a case
  teaches the compiler the exact type, no cast needed.
- Inference inside GADT matches can need a type ascription on the scrutinee; add it on the public
  signature, not the local (see `scala#type-ascription-restraint`).

## Union types
**Encodes:** "one of these, with no third option" — without a wrapper ADT.
```scala
type Json = String | Double | Boolean | Null
def render(failure: ParseError | SchemaError): String = failure match
  case parse: ParseError   => parse.detail
  case schema: SchemaError => schema.detail
```
- Untagged: `A | B` has no runtime wrapper, so matching relies on the runtime classes being
  distinct. For same-erasure cases (`List[Int] | List[String]`) use a sealed ADT instead.
- `T | Null` is the explicit-nullability encoding under `-Yexplicit-nulls`; prefer `Option[T]` for
  ordinary absence (see `fail-loud`) and reserve `| Null` for Java-interop boundaries.
- A closed union in an error channel lets callers handle each case — strictly better than `Either[String, A]`.

## Intersection types
**Encodes:** "all of these capabilities at once" — ad-hoc composition without a bespoke combined trait.
```scala
trait HasId        { def id: Long }
trait HasTimestamp { def createdAt: Instant }

def audit(entity: HasId & HasTimestamp): LogLine =
  LogLine(entity.id, entity.createdAt)
```
- `A & B` is commutative and order-independent; members with the same name must have compatible types.
- Use it to require capabilities at a call site instead of growing a deep inheritance tree; pairs
  naturally with `using` evidence (a method that needs both `Logger` and `Clock`).

## Phantom types / typestate
**Encodes:** a protocol's legal state transitions — an out-of-order call doesn't compile.
```scala
sealed trait Draft
sealed trait Submitted

class Form[State] private (val fields: Map[String, String]):
  def set(key: String, value: String)(using State =:= Draft): Form[Draft] =
    new Form(fields.updated(key, value))
  def submit(using State =:= Draft): Form[Submitted] =
    new Form(fields)

object Form:
  def draft: Form[Draft] = new Form(Map.empty)
```
- The state parameter is *phantom*: it appears only in signatures, never in a field, so it is
  erased and costs nothing at runtime.
- The empty marker traits carry no cases, so the prefer-enum rule — about closed ADTs — doesn't
  apply; there's nothing to enumerate.
- `=:=` / `<:<` are the evidence values that gate each transition; `using` summons them.

## Match types
**Encodes:** a type computed from another type — type-level pattern matching, mostly for library APIs.
```scala
type Elem[C] = C match
  case String      => Char
  case Array[t]    => t
  case Iterable[t] => t
```
- Powerful but niche; reach for it only when a return type genuinely depends on an input type
  (a heterogeneous API). For ordinary domain modeling the rows above are almost always the answer.
- Recursion and reduction can be fragile — keep match types shallow and test the inferred types.

## Context functions
**Encodes:** "this computation requires a capability" — a lightweight `Reader` whose dependency is
visible in the *type*, with no runtime wrapper.
```scala
type Configured[A] = Config ?=> A

def databaseUrl: Configured[String] = summon[Config].get("db.url")

given Config = loadConfig()
val url: String = databaseUrl   // Config supplied implicitly
```
- `Config ?=> A` desugars to a function taking a `using Config`; the compiler inlines it, so it is
  cheaper and more composable than `Reader[Config, A]`.
- **Defer to `scala#using-parameters`** for the project convention on naming vs forwarding `using`
  params; use context-function *types* (`?=>`) when you want the dependency to read as part of the
  return type rather than a parameter list.

## Multiversal equality / `CanEqual`
**Encodes:** "these two types may be compared with `==`" — turning accidental cross-type comparisons
into compile errors.
```scala
import scala.language.strictEquality   // or -language:strictEquality

case class UserId(value: Long) derives CanEqual
case class OrderId(value: Long) derives CanEqual

UserId(1) == UserId(1)   // ok
// UserId(1) == OrderId(1)   // compile error: no CanEqual[UserId, OrderId]
```
- Under strict equality only types with a `CanEqual` instance can be `==`-compared.
- The project convention is to compare domain types with Cats `===` and provide `given Eq` — see
  `scala#cats-suffix-syntax`. `CanEqual`/strict-equality is the language-level lever that makes the
  bare `==` mistake impossible in the first place; reach for it on value types that are easy to mix.

## `inline` / `compiletime.error`
**Encodes:** a check that runs at compile time and fails the build with a custom message.
```scala
import scala.compiletime.{error, constValue}

inline def requirePositive[N <: Int]: Unit =
  inline if constValue[N] <= 0 then error("expected a positive literal")
```
- This is the machinery Iron uses for literal validation (`Email("user@example.com")` validates at
  compile time). Almost always reach for **`iron`** rather than hand-writing inline checks; drop to
  raw `inline`/`error` only when building a genuinely new compile-time constraint.

## Smart constructors
**Encodes:** "a value of this type has passed its check" — construction is the only way in, and it
returns the refined type (or an error), never an unchecked value.
```scala
opaque type Percentage = Double
object Percentage:
  def either(value: Double): Either[String, Percentage] =
    Either.cond(value >= 0 && value <= 100, value, s"$value not in 0..100")
```
- The constructor is the single parse point; downstream code receives `Percentage` and never
  re-checks the bound — the type is the proof (pattern 1 in `SKILL.md`).
- **Defer to `iron`**: `.option`/`.either`/`apply` on a refined companion are the project's standard
  smart constructors. Don't re-implement them; a bespoke one means future readers miss the idiom.
