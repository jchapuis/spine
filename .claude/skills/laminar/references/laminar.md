# Laminar / Airstream Reference

Sources:
- https://laminar.dev/documentation
- https://github.com/lancewalton/dot-files
- https://github.com/nguyenyou/laminar-mill

## Table of Contents
- [Rendering Lists with split](#rendering-lists-with-split)
- [Controlled Inputs](#controlled-inputs)
- [Sampling Signal State in Handlers](#sampling-signal-state-in-handlers)
- [Stream Transformation over Var.set](#stream-transformation-over-varset)
- [Flattening Observables](#flattening-observables)
- [Signal.changes Gotcha](#signalchanges-gotcha)
- [Derived Signals](#derived-signals)
- [Conditional Rendering](#conditional-rendering)
- [Combining Signals](#combining-signals)
- [Var Updates](#var-updates)
- [Error Handling](#error-handling)
- [Component Structure](#component-structure)
- [Var Signal Extraction](#var-signal-extraction)
- [Observer Subscriptions](#observer-subscriptions)

---

## Rendering Lists with split

**Always** use `split` with a stable key when rendering `Signal[List[T]]`. Never map directly
to child elements — it recreates the entire DOM subtree on every emission, causing **focus loss
on every keystroke** for form inputs.

```scala
// Bad — entire list re-rendered on any update; inputs lose focus
children <-- itemsSignal.map { items =>
  items.map { item =>
    input(
      value := item.name,
      onInput.mapToValue --> { v => updateItem(item.id, v) }
    )
  }
}

// Good — split tracks items by stable key; only changed elements update
children <-- itemsSignal.split(_.id) { (id, initial, itemSignal) =>
  input(
    controlled(
      value <-- itemSignal.map(_.name),
      onInput.mapToValue --> { v => updateItem(id, v) }
    )
  )
}
```

Rules:
1. Every `Signal[List[T]]` → DOM **must** use `split` with a stable key (ID, not index).
2. Duplicate or unstable keys cause warnings and undefined behavior.
3. `Var.split` gives a writable child `Var[T]` — updates propagate to the parent list.
4. When items lack a natural key, use `splitByIndex` (but reordering recreates elements).
5. Child signal only fires when that specific item changes (`distinctCompose` by default).

```scala
// Var.split — writable child var, changes propagate up
todosVar.split(_.id) { (id, initial, todoVar: Var[Todo]) =>
  checkbox(
    checked <-- todoVar.signal.map(_.done),
    onClick --> Observer(_ => todoVar.update(todo => todo.copy(done = !todo.done)))
  )
}
```

---

## Controlled Inputs

Use `controlled` when an input's backing `Var` can be updated from **anywhere other than the
input itself** (buttons, external events, signal updates). An uncontrolled `value := initial`
only sets the DOM value once at creation — subsequent `Var.set()` calls update the `Var` but
the DOM input remains unchanged.

```scala
// Bad — clicking "Suggest" updates the Var but the input still shows old text
val pattern: Var[String] = Var("")
input(
  value := "",
  onInput.mapToValue --> pattern
)
button("Suggest", onClick --> Observer(_ => pattern.set("inferred_value")))

// Good — controlled reflects all writes to the Var
val pattern: Var[String] = Var("")
input(
  controlled(
    value <-- pattern.signal,
    onInput.mapToValue --> pattern
  )
)
button("Suggest", onClick --> Observer(_ => pattern.set("inferred_value")))
```

Reserve `value := initial` only for truly static values with no programmatic updates.

---

## Sampling Signal State in Handlers

Use `withCurrentValueOf` to read the current value of a signal at event time. Avoids stale
closures and hidden dependencies.

```scala
// Bad — signal value captured once at element creation; stale after any update
val currentItem = itemSignal.now()
onClick.mapTo(doSomething(currentItem)) --> observer

// Good — samples signal on each click
onClick.compose(_.withCurrentValueOf(itemSignal).map { case (_, item) =>
  doSomething(item)
}) --> observer
```

Chain for multiple signals:
```scala
onClick.compose(
  _.withCurrentValueOf(aSignal).withCurrentValueOf(bSignal)
   .map { case ((_, a), b) => doSomething(a, b) }
) --> observer
```

**Never call `.now()` inside combinators** — it creates hidden dependencies Airstream can't track.

```scala
// Bad — hidden dependency
nameSignal.map { name => s"$name clicked ${countVar.now()} times" }

// Good — reactive
nameSignal.withCurrentValueOf(countSignal)
  .map { case (name, count) => s"$name clicked $count times" }
```

---

## Stream Transformation over Var.set

When an event handler's only purpose is to transform a value and write it to a `Var`, lift
the transformation into the stream instead. `Var[A]` is an `Observer[A]`, so `-->` can write
directly to it.

| Smell | Refactored |
|---|---|
| `onChange.mapToValue --> { v => myVar.set(f(v)) }` | `onChange.mapToValue.map(f) --> myVar` |
| `onClick --> { _ => myVar.set(constant) }` | `onClick.mapTo(constant) --> myVar` |
| `stream --> { event => myVar.set(Some(event)) }` | `stream.map(Some(_)) --> myVar` |

```scala
// Bad
onChange.mapToValue --> { v =>
  if (v.isEmpty) selectedId.set(None)
  else selectedId.set(Some(ID(v)))
}

// Good
onChange.mapToValue.map(ID.fromString[MyType]) --> selectedId

// Bad
onClick --> { _ => suggestResults.set(None) }

// Good
onClick.mapTo(None) --> suggestResults
```

Reserve `Var.set` inside `-->` only when multiple `Var`s must be updated, side effects beyond
a single write are needed, or conditional logic determines whether to write at all.

---

## Flattening Observables

Generic `flatMap` is **disabled by design**. Always choose an explicit flattening strategy.

```scala
// Good
stream.flatMapSwitch(x => fetchData(x))  // cancel previous, use latest result
stream.flatMapMerge(x => fetchData(x))   // run all concurrently

// Won't compile
stream.flatMap(x => fetchData(x))
```

- `flatMapSwitch` — user input, search queries, navigation (cancel stale requests)
- `flatMapMerge` — independent operations that should all complete

---

## Signal.changes Gotcha

`.changes` is a `def`, not a `lazy val`. Each call returns a **new stream instance**.

```scala
// Bad — two different stream instances; subscribers won't see the same events
signal.changes.foreach(handler1)
signal.changes.foreach(handler2)

// Good — capture once
val changes = signal.changes
changes.foreach(handler1)
changes.foreach(handler2)
```

---

## Derived Signals

Always `.distinct` when deriving `Signal[Primitive]` — prevents redundant downstream updates
when the parent emits but the derived value hasn't changed.

```scala
val isValid: Signal[Boolean] = formData.map(_.isValid).distinct
val userName: Signal[String]  = user.map(_.name).distinct
```

Use `.not` to invert `Signal[Boolean]`:

```scala
val isHidden: Signal[Boolean] = isVisible.not.distinct
```

Use `compose` / `composeChanges` for chained transformations:

```scala
signal.compose(_.map(transform).filter(predicate).distinct)
signal.composeChanges(_.delay(100.millis))
```

---

## Conditional Rendering

```scala
// Boolean signal
child <-- isVisible.map(if (_) renderContent else emptyNode)

// Dynamic text — use text <--, not child <--
text <-- isLoading.map(if (_) "Loading..." else "Done")

// Option signal
child.maybe <-- maybeUser.map(_.map(renderUser))
```

Only use `splitBoolean` / `splitOption` for expensive subtrees (large DOM trees, heavy
computation) — overkill for simple elements.

```scala
child <-- isVisible.splitBoolean(
  whenTrue  = _ => renderExpensiveDashboard,
  whenFalse = _ => emptyNode
)
```

---

## Combining Signals

Prefer `combineWithFn` over `combineWith(...).map(...)`:

```scala
// Good
val fullName: Signal[String] = firstName.combineWithFn(lastName)((f, l) => s"$f $l")

// Bad — intermediate tuple overhead
val fullName: Signal[String] = firstName.combineWith(lastName).map { case (f, l) => s"$f $l" }
```

---

## Var Updates

Prefer `update()` over `now()` + `set()` (atomic):

```scala
// Good
onClick --> Observer(_ => countVar.update(_ + 1))

// Bad — two operations, non-atomic
onClick --> Observer { _ => countVar.set(countVar.now() + 1) }
```

Use `tryUpdater` when the `Var` may be in a failed state:

```scala
myVar.tryUpdater { (currTry, next) => currTry.map(curr => curr ++ next) }
```

Batch multiple `Var` updates to avoid intermediate states:

```scala
// Good — single transaction
Var.set(
  itemsVar -> List.empty,
  pageVar  -> 0
)

// Bad — two separate updates, intermediate state visible
itemsVar.set(List.empty)
pageVar.set(0)
```

---

## Error Handling

```scala
stream.recover { case _: NetworkError => Some(cachedValue) }

val asTry:    Signal[Try[A]]           = stream.recoverToTry
val asEither: Signal[Either[Throwable, A]] = stream.recoverToEither
```

For `Signal[Try[A]]`:
```scala
trySignal.mapSuccess(transform)
trySignal.mapFailure(transformErr)
trySignal.foldTry(onError, onSuccess)
```

---

## Component Structure

Define reusable components as classes with a `render()` method. Expose state as
`Signal` (read) and `Observer` (write) — never pass `Var` as a prop.

```scala
// Good — separate read/write concerns
class TextInput(
  value: Signal[String],
  onChange: Observer[String]
) extends LaminarComponent:
  def render(): HtmlElement =
    input(
      controlled(
        value <-- value,
        onInput.mapToValue --> onChange
      )
    )

// Usage
val textVar = Var("")
TextInput(textVar.signal, textVar.writer).render()

// Bad — couples component to a specific Var
class TextInput(valueVar: Var[String]) extends LaminarComponent:
  ...
```

When parent needs to observe internal state, expose it as a public `Signal`:

```scala
class Toggle(initial: Boolean = false) extends LaminarComponent:
  private val checkedVar = Var(initial)
  val checkedSignal: Signal[Boolean] = checkedVar.signal

  def render(): HtmlElement =
    input(
      typ := "checkbox",
      checked <-- checkedSignal,
      onClick.mapToChecked --> checkedVar.writer
    )
```

---

## Var Signal Extraction

Extract `var.signal` to a named `val` when used in more than one place:

```scala
// Good — named val, clear read/write separation
val countVar    = Var(0)
val countSignal = countVar.signal

div(
  text <-- countSignal.map(_.toString),
  cls  <-- countSignal.map(count => if count > 10 then "high" else "low"),
  button(onClick --> Observer(_ => countVar.update(_ + 1)))
)

// Bad — repeated .signal calls scattered through code
val countVar = Var(0)
div(
  text <-- countVar.signal.map(_.toString),
  cls  <-- countVar.signal.map(count => if count > 10 then "high" else "low"),
  ...
)
```

---

## Observer Subscriptions

Use explicit `Observer(...)` on the right-hand side of `-->`:

```scala
// Good
filterSignal --> Observer(filter => fetchData(filter))

// Bad — bare function, less explicit
filterSignal --> (filter => fetchData(filter))
```
