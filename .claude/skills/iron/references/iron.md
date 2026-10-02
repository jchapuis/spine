# Iron — Refined Types Reference (Scala 3)

Sources:
- https://github.com/iltotore/iron/blob/main/README.md
- https://github.com/iltotore/iron/blob/main/docs/_docs/reference/newtypes.md
- https://github.com/iltotore/iron/blob/main/docs/_docs/modules/skunk.md
- https://github.com/iltotore/iron/blob/main/docs/_docs/modules/cats.md
- https://github.com/iltotore/iron/blob/main/docs/_docs/modules/doobie.md

## Table of Contents
- [Core concepts](#core-concepts)
- [Opaque newtypes](#opaque-newtypes)
- [Type aliases](#type-aliases)
- [Constraint catalogue](#constraint-catalogue)
- [Constraint composition](#constraint-composition)
- [Runtime refinement](#runtime-refinement)
- [Skunk integration](#skunk-integration)
- [Cats integration](#cats-integration)
- [Circe integration](#circe-integration)
- [Tapir integration](#tapir-integration)
- [Import order](#import-order)

---

## Core concepts
- `T :| C` is the refined type: a `T` that satisfies constraint `C`.
- Refinement at **compile time** for literal values: `val x: Int :| Positive = 5` — verified by the compiler.
- Refinement at **runtime** for external values: `runtimeInt.refineEither[Positive]` — returns `Either[String, Int :| Positive]`.
- **Opaque newtypes** (`RefinedType`) add a new nominal type layer on top of the refined type — values of different newtypes are not interchangeable even if they share the same base and constraint.
- **Type aliases** (`type Alias = Base :| Constraint`) are structural and substitutable — use only when the constraint is a shared invariant, not a distinct domain concept.

---

## Opaque newtypes

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// Canonical pattern (Iron 3.x):
type UserId = UserId.T
object UserId extends RefinedType[String, Not[Blank]]

// Usage:
def findUser(id: UserId): IO[User] = ???

val id: UserId = UserId.applyUnsafe("abc-123")      // trusted value
val parsed: Either[String, UserId] = UserId.either("abc-123") // runtime validation

// Newtypes prevent mixing even with same underlying constraint:
type Email = Email.T
object Email extends RefinedType[String, Not[Blank]]

val userId: UserId = ???
val email: Email = userId  // compile error: Email expected, got UserId
```

---

## Type aliases

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// For shared, generic constraints — no domain identity:
type NonBlankString = String :| Not[Blank]
type PositiveInt    = Int :| Positive

// Transparent subtype — no conversion needed:
val s: NonBlankString = "hello"        // compile-time OK for literals
val raw: String = s                    // widening is free (transparent subtype)
```

---

## Constraint catalogue

```scala
// Numeric
Positive          // > 0
StrictlyPositive  // >= 0 (alias: NonNegative)
Negative          // < 0
Greater[N]        // > N  (literal type)
Less[N]           // < N
GreaterEqual[N]   // >= N
LessEqual[N]      // <= N
Interval.Open[L, H]    // (L, H)
Interval.Closed[L, H]  // [L, H]

// String / Char
Not[Blank]        // non-blank (non-null, non-empty, non-whitespace-only)
MinLength[N]
MaxLength[N]
Length[N]         // exact
ForAll[Letter]    // all chars satisfy Letter constraint
ForAll[Digit]
StartWith[S]
EndWith[S]
Match[R]          // regex match (literal string type)

// Collection
Empty
NonEmpty
MinSize[N]
MaxSize[N]
Contain[V]

// Logic
Not[C]
C1 & C2  // both
C1 | C2  // either
```

---

## Constraint composition

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// AND composition with &:
type NonBlankMax64 = String :| (Not[Blank] & MaxLength[64])

// Opaque newtype with composed constraint:
type Name = Name.T
object Name extends RefinedType[String, Not[Blank] & MaxLength[100]]

// Custom constraint (advanced):
final class Uuid
object Uuid:
  given Constraint[String, Uuid] with
    override inline def test(value: String): Boolean =
      value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    override inline def message: String = "Should be a valid UUID"
```

---

## Runtime refinement

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

val raw: String = fetchFromRequest()

// Functional (preferred at boundaries):
val result: Either[String, String :| Not[Blank]] = raw.refineEither[Not[Blank]]

// Opaque newtype from external value:
val userId: Either[String, UserId] = UserId.either(raw)

// Unsafe (only for values you control or have already validated):
val trusted: String :| Not[Blank] = raw.refineUnsafe[Not[Blank]]
```

---

## Skunk integration

```scala
import skunk.*
import skunk.codec.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.given  // brings refined codec extensions into scope

// Inline refinement at query definition:
type Username = String :| Not[Blank]
val q: Query[Void, Username] = sql"SELECT name FROM users".query(varchar.refined)

// Codec for an opaque newtype (define in companion):
type PositiveInt = PositiveInt.T
object PositiveInt extends RefinedType[Int, Positive]:
  given codec: Codec[PositiveInt] = int4.refined[Positive]

// Codec for a case class with refined fields:
case class User(name: String :| Not[Blank], age: Int :| Positive)
given Codec[User] = (varchar.refined[Not[Blank]] *: int4.refined[Positive]).to[User]
```

---

## Cats integration

```scala
import cats.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.cats.given  // Eq, Order, Show, Hash for refined types

// Refined types get Eq/Order/Show derived from the underlying type:
val a: Int :| Positive = 1
val b: Int :| Positive = 2
(a === b)  // uses derived Eq
```

---

## Circe integration

```scala
import io.circe.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.circe.given  // Encoder/Decoder for refined types

// Decoder validates at runtime during JSON parsing:
case class Request(name: String :| Not[Blank], count: Int :| Positive) derives Codec.AsObject
```

---

## Tapir integration

```scala
import sttp.tapir.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import sttp.tapir.codec.iron.*   // Schema and Codec for Iron refined types

// Schema is derived automatically for refined types:
val ep = endpoint.get
  .in("users" / path[UserId]("id"))
  .out(jsonBody[User])
```

---

## Import order

Follow project guidelines — Cats first, then Iron, then integration `given`s:

```scala
import cats.syntax.option.*                  // specific cats.syntax module, not the wildcard
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.given   // or cats.given / circe.given
```
