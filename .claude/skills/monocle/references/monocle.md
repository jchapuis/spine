# Monocle — Optics Reference (Scala 3)

Sources:
- https://github.com/optics-dev/monocle/blob/master/docs/src/main/mdoc/focus.md
- https://github.com/optics-dev/monocle/blob/master/docs/src/main/mdoc/optics/lens.md
- https://github.com/optics-dev/monocle/blob/master/docs/src/main/mdoc/optics/prism.md

## Table of Contents
- [Optic types at a glance](#optic-types-at-a-glance)
- [focus macro (preferred)](#focus-macro-preferred)
- [Lens](#lens)
- [Optional](#optional)
- [Prism](#prism)
- [Traversal](#traversal)
- [Iso](#iso)
- [Optic composition](#optic-composition)
- [Common accessors](#common-accessors)
- [Immutable collection updates](#immutable-collection-updates)
- [UI state patterns](#ui-state-patterns)

---

## Optic types at a glance

| Optic | Source | Focus | get | set/modify |
|-------|--------|-------|-----|-----------|
| `Iso[S, A]` | `S` | always exactly `A` | `get` | `replace` |
| `Lens[S, A]` | `S` | always exactly `A` | `get` | `replace`, `modify` |
| `Prism[S, A]` | `S` (sum type) | `Option[A]` | `getOption` | `replace`, `modify` |
| `Optional[S, A]` | `S` | `Option[A]` | `getOption` | `replace`, `modify` |
| `Traversal[S, A]` | `S` | `List[A]` (0–n) | `getAll` | `replace`, `modify` |

Composition of two optics produces the most general type that covers both.

---

## focus macro (preferred)

```scala
import monocle.syntax.all.*

case class Address(streetNumber: Int, streetName: String)
case class User(name: String, address: Address)
case class UserOpt(name: String, address: Option[Address])

val anna = User("Anna", Address(12, "High Street"))

// Replace a nested field:
anna.focus(_.address.streetNumber).replace(13)
// User("Anna", Address(13, "High Street"))

// Modify a nested field:
anna.focus(_.address.streetNumber).modify(_ + 1)

// Modify a field at the top level:
anna.focus(_.name).replace("Bob")

// Navigate into Option:
val annaOpt = UserOpt("Anna", Some(Address(12, "High Street")))
annaOpt.focus(_.address.some.streetNumber).modify(_ + 1)
// If address is None, the modification is a no-op.

// Deep path:
anna.focus(_.address.streetName).modify(_.toUpperCase)
```

---

## Lens

```scala
import monocle.Lens
import monocle.macros.GenLens

case class Point(x: Double, y: Double)
case class Shape(center: Point, radius: Double)

// Auto-derive with GenLens:
val centerLens: Lens[Shape, Point] = GenLens[Shape](_.center)
val xLens: Lens[Point, Double]     = GenLens[Point](_.x)

// Manual:
val radiusLens: Lens[Shape, Double] = Lens[Shape, Double](_.radius)(r => s => s.copy(radius = r))

// Operations:
val circle = Shape(Point(0, 0), 5.0)
centerLens.get(circle)             // Point(0, 0)
centerLens.replace(Point(1, 1))(circle)
centerLens.modify(p => p.copy(x = p.x + 1))(circle)

// Compose:
val shapeX: Lens[Shape, Double] = centerLens.andThen(xLens)
shapeX.modify(_ + 10)(circle)
```

---

## Optional

```scala
import monocle.Optional

// Optional targets a field that may not exist:
case class Config(timeout: Option[Int])

val timeoutOpt: Optional[Config, Int] =
  Optional[Config, Int](_.timeout)(t => c => c.copy(timeout = Some(t)))

val cfg = Config(Some(30))
timeoutOpt.getOption(cfg)          // Some(30)
timeoutOpt.replace(60)(cfg)        // Config(Some(60))
timeoutOpt.replace(60)(Config(None))  // Config(Some(60)) — inserts!

// Via focus and .some:
cfg.focus(_.timeout.some).modify(_ * 2)
```

---

## Prism

```scala
import monocle.Prism

// Prism for a sum-type variant:
sealed trait Json
case class JStr(value: String) extends Json
case class JNum(value: Double) extends Json
case object JNull               extends Json

val jStr: Prism[Json, String] =
  Prism.partial[Json, String] { case JStr(s) => s }(JStr.apply)

jStr.getOption(JStr("hello"))    // Some("hello")
jStr.getOption(JNull)            // None
jStr.replace("world")(JStr("hi"))
jStr.modify(_.toUpperCase)(JStr("hello"))  // JStr("HELLO")
jStr.modify(_.toUpperCase)(JNum(3.14))     // JNum(3.14) — no-op
```

---

## Traversal

```scala
import monocle.Traversal
import monocle.syntax.all.*

case class Cart(items: List[CartItem])
case class CartItem(name: String, price: Double)

// All elements of a List:
val allItems: Traversal[Cart, CartItem] =
  Traversal.fromTraverse[List, CartItem].compose(GenLens[Cart](_.items))

allItems.getAll(cart)             // List[CartItem]
allItems.modify(_.copy(price = _.price * 0.9))(cart)  // 10% discount on all

// With focus — index into a list:
cart.focus(_.items.index(0).price).replace(9.99)
```

---

## Iso

```scala
import monocle.Iso

// Bijection between two representations:
val stringToChars: Iso[String, List[Char]] =
  Iso[String, List[Char]](_.toList)(_.mkString)

stringToChars.get("hello")        // List('h','e','l','l','o')
stringToChars.reverseGet(List('h','i'))  // "hi"

// Common use: wrapping/unwrapping value types
case class Wrapper(value: Int) extends AnyVal
val wrapIso: Iso[Int, Wrapper] = Iso[Int, Wrapper](Wrapper.apply)(_.value)
```

---

## Optic composition

Composition uses `.andThen`; the result type is determined by the weakest optic in the chain:

| Left | Right | Result |
|------|-------|--------|
| Lens | Lens | Lens |
| Lens | Optional | Optional |
| Lens | Prism | Optional |
| Optional | Optional | Optional |
| Prism | Prism | Prism |

```scala
val centerX: Lens[Shape, Double] = centerLens.andThen(xLens)
val optStreetNum: Optional[UserOpt, Int] =
  GenLens[UserOpt](_.address).andThen(monocle.std.option.some[Address]).andThen(GenLens[Address](_.streetNumber))
```

---

## Common accessors

```scala
import monocle.syntax.all.*

// Option — navigate into Some:
opt.focus(_.field.some)           // Optional targeting inside Option

// List element by index:
list.focus(_.index(2))            // Optional

// Map value by key:
map.focus(_.index("key"))         // Optional
```

---

## UI state patterns

In Laminar + Waypoint, Monocle is used to update deeply nested UI state without `Var`-per-field:

```scala
import monocle.syntax.all.*

case class AppState(user: User, settings: Settings, cart: Cart)

// Single top-level Var, update nested fields via focus:
val stateVar: Var[AppState] = Var(AppState(...))

// Update nested field from an event stream:
someStream
  .map(newName => stateVar.now().focus(_.user.name).replace(newName))
  --> stateVar.writer

// Compose lens for reuse across components:
val cartItemPriceAt: Int => Optional[AppState, Double] =
  idx => GenLens[AppState](_.cart.items).andThen(monocle.std.list.index(idx)).andThen(GenLens[CartItem](_.price))

// Modify in place:
stateVar.update(_.focus(_.cart.items.index(0).price).modify(_ * 0.9))
```
