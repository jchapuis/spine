---
name: laminar
description: Laminar/Airstream reactive UI patterns for Scala.js frontends. Use when writing, reviewing, or refactoring Laminar components, Airstream signals/streams, Var state management, or Scala.js frontend code.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['com\.raquo\.laminar', 'com\.raquo\.airstream']

mechanical_checks:
  - id: laminar.json-stringify
    tool: ripgrep
    severity: error
    pattern: '\bjs\.JSON\.stringify\s*\('
    files_must_import: ['com\.raquo\.laminar']
    message: 'Never `js.JSON.stringify` Airstream/DOM values — cyclic refs throw TypeError and poison the subscriber chain'
    references: ['laminar#never-json-stringify-airstream-or-dom-values']
  - id: laminar.raw-set-timeout
    tool: ripgrep
    severity: warning
    pattern: '\bjs\.timers\.set(Timeout|Interval)\s*\('
    files_must_import: ['com\.raquo\.laminar']
    message: 'Use Airstream `EventStream.periodic` / `.delay` instead of raw `js.timers`'
    references: ['laminar#airstream-primitives']
  - id: laminar.now-inside-combinator
    tool: ripgrep
    severity: warning
    pattern: '\.(?:map|filter|filterNot|collect|flatMap\w*|foreach|scanLeft|fold\w*|takeWhile|dropWhile|split\w*)\s*[({:][^\n]*(?<!performance)(?<!Date)\.now\(\)'
    files_must_import: ['com\.raquo\.laminar', 'com\.raquo\.airstream']
    paths_exclude: ['**/src/test/**']
    message: 'No `.now()` inside combinators — use `withCurrentValueOf` / `sample` so the dependency is declared. A one-shot read at mount, or a read of a `Var` nothing observes, is not this — leave those alone'
    references: ['laminar#sampling-state-at-event-time']
  - id: laminar.child-arrow-map-case
    tool: ripgrep
    severity: warning
    pattern: 'child(?:\.maybe)?\s*<--\s*\S+?\.map\s*[\{:]\s*case\b'
    files_must_import: ['com\.raquo\.laminar']
    paths_exclude: ['**/src/test/**']
    message: '`child <-- signal.map { case A => ... case B => ... }` rebuilds the subtree on every emission and loses local UI state — use `signal.splitMatchOne` with `handleType` / `handleCase` so each variant has a stable mount'
    references: ['laminar#rendering-sealed-adt-signals']
---

# Laminar / Airstream (Scala.js)

## Frontend package layout

The frontend mirrors the server's layered discipline (`server-architecture`) — reactive rendering is one layer, not the whole app:

- **`components/` holds only components** — things whose job is to render (`apply` returns an `HtmlElement`/`SvgElement`). A file in `components/` that is mostly data types, computation, or service calls is *not a component*: it's model + logic wearing a component's clothes.
- **Where the rest goes**: domain data types → `model/`; business logic and orchestration → algebras/services (trait + impl, exactly as on the server); formatting/accessor helpers on a domain type → `extension` blocks next to the type; cross-cutting client services (storage, i18n, routing state) → their own service package.
- **Viewmodel discipline** — when a component has a presenter/viewmodel, *all* presented business data flows through it. Deriving some fields from the viewmodel and computing others ad hoc in the view splits the source of truth.
- **Var-parameter tell** — `Var`s/signals threaded through several private `def`s are the signal for the class-scope refactor (`scala#class-scope-for-helper-bundles`): capture them once in class scope. Wire signals once and recycle them; a signal recreated on every call re-subscribes and re-computes.
- **State bundles** — a cluster of loose `Var`s + derived signals that always travel together belongs in a named state class, not as parallel locals.
- **i18n** — every user-facing string goes through the i18n mechanism (no hardcoded UI copy). When adding entries, keep tone and pronoun register consistent with the existing translations in *every* language, not just the one you're editing.

## Quick start
- Use `split` with a stable key whenever rendering a `Signal[List[T]]` — never `.map` to child elements.
- Use `controlled` for any input whose backing `Var` can be updated from outside the input itself.
- Use `withCurrentValueOf` to sample signal values inside event pipelines — never `.now()` inside combinators.
- Use `flatMapSwitch` / `flatMapMerge` explicitly — generic `flatMap` is disabled by design.
- Prefer stream transformation + `-->` over calling `Var.set` inside `-->` handlers. When the handler's only purpose is to compute a value and write it to a `Var[A]`, map the computation into the stream — `Var[A]` is itself an `Observer[A]`:
  ```scala
  // Bad — imperative observer with if/else branching
  onBlur.mapToValue --> Observer[String]: email =>
    if email.nonEmpty && email.refineEither[ValidEmail].isLeft then emailError.set(true)
    else emailError.set(false)

  // Good — map the predicate; Var[Boolean] is Observer[Boolean]
  (onBlur.mapToValue.map: email =>
    email.nonEmpty && email.refineEither[ValidEmail].isLeft) --> emailError
  ```
  Note: outer parentheses are required when a multi-line `.map:` lambda precedes `-->`.
- Read `references/laminar.md` for detailed patterns, gotchas, and examples.

## Workflow
1. Model state as `Var[A]`; extract `.signal` to a named `val` when used in multiple places.
2. Render lists with `split`; render conditional content with `child <--` / `child.maybe <--`.
3. Wire inputs with `controlled`; sample state at event time with `withCurrentValueOf`.
4. Choose flattening strategy explicitly (`flatMapSwitch` for latest-wins, `flatMapMerge` for concurrent).
5. Derive signals with `.map(...).distinct`; use `.not` to invert `Signal[Boolean]`.
6. Handle errors with `recover` / `recoverToTry`; batch `Var` updates with `Var.set(...)`.

## Component structure
- **Extract logical sections as private `def`s on the companion object**, not as local `val`s inside `apply`. Each major visual region (`backgroundPhoto`, `ctaButtons`, `complianceBadge`, etc.) becomes a `private def` that takes only what it needs (state `Var`s, `using` context). This keeps `apply` shallow, avoids inflating its cyclomatic complexity, and makes each section independently readable. Always use `def` rather than `val` for `HtmlElement` members — elements cannot be safely shared across multiple mounts. If those helper `def`s start threading the same `Var`s / `using` context across three or more methods, escalate to the class-scope refactor in `scala#class-scope-for-helper-bundles` — lift the helpers into a class whose constructor captures the shared inputs, and keep `apply` on the object as the entry point.

## Key rules
- **`split` is mandatory** for `Signal[List[T]]` → DOM. Mapping directly recreates the entire subtree on every emission, causing focus loss on text inputs.
- **`controlled` is mandatory** when a `Var` backing an input can be modified from anywhere other than that input.
- **`Signal.changes` is a `def`**, not a `lazy val` — capture it to a `val` before subscribing more than once.
- **`flatMapSwitch` directly on the signal — not on `.changes`**. `signal.changes` does not emit during the same transaction the signal was created in, so any subscriber that mounts mid-transaction never receives the initial value. Use `signal.flatMapSwitch(...)` so the current value is propagated immediately.
  ```scala
  // Bad — mid-transaction mounts miss the initial value
  signal.changes.flatMapSwitch(buildChild)

  // Good — current value propagates on subscription
  signal.flatMapSwitch(buildChild)
  ```
- **No `.now()` inside combinators** — use `withCurrentValueOf` / `sample` to keep reactive dependencies explicit. Outside one — a one-shot read at mount, a `Var` nothing observes — `.now()` is the point, not the violation.
- **Always `.distinct`** when deriving `Signal[Primitive]` (Boolean, String, Int, Double).
- **Pass `Signal` + `Observer` separately** instead of `Var` as component props.
- Use explicit `Observer(...)` on the right-hand side of `-->`.

## Sampling state at event time

`.now()` reads a cell without declaring a dependency on it. Inside a combinator that is a bug waiting for a race: the pipeline reruns only when its *declared* sources fire, so the sampled value is whatever happened to be there at that moment, and the same input yields different output depending on emission order. `withCurrentValueOf` / `sample` put the value into the event, where the dependency is visible and the ordering is Airstream's problem rather than yours.

```scala
// Bad — `pending` is read but never declared; nothing reruns when it changes
idles.filter(_ => pending.now()).foreach(_ => resolveDeepLinked(boreholeID, pending))

// Good — every cell the decision reads is sampled into the event
idles
  .sample(pending, tilesReady)
  .filter((isPending, _) => isPending)
  .foreach((_, tilesRendered) => resolveDeepLinked(boreholeID, pending, tilesRendered))
```

**Outside a combinator, `.now()` is the point.** A one-shot read at mount has no pipeline to declare a dependency in — the style URL a map constructor needs before any stream exists, the deep link the URL already carried on arrival. So does a read of a `Var` nothing observes, which is a mutable cell rather than a reactive source: a `requestAnimationFrame` loop holding its frame handle and hover levels is imperative by nature. Where such a cell is also *written* inside an observer it should not be a `Var` at all — see *State cells*.

The mechanical rule matches combinator-shaped calls on one line, so it cannot see a lambda that spans lines and it does not try to. A finding that turns out to be one of the reads above is a false positive — leave it alone rather than contorting the call site to satisfy the pattern.


## State cells

Two kinds of cell hold state, and the difference is when a write becomes readable.

**`Var` is the reactive cell**, and it stays the default even when nothing observes it — a captured JS instance, a widget handle kept only so it can be closed later — *provided the write does not happen inside an observer*. It is already in scope through `L.*`, and reading it with `.now()` outside a combinator is exactly what `.now()` is for. Do not reach for `AtomicReference`: Scala.js is single-threaded, so it guards nothing a `Var` would not, and it pulls `java.util.concurrent` in to do less.

**But a `Var` written inside an observer and read outside the transaction graph is a bug.** Airstream schedules `Var.set` against the transaction that is running, so the write does not land before the observer returns — `.now()` straight afterwards reads the old value, and the write can be dropped outright. That is fatal when the reader is not reactive at all: a `requestAnimationFrame` render callback, a `ResizeObserver`, a third-party callback. In one app this silently killed the custom WebGL layers — `setContent` wrote the field to draw from inside a signal observer, the very next frame read `None`, the layer concluded it had nothing to draw and stopped asking for frames.

**That cell is a plain `var`** — written and read in the same breath, nothing subscribed, no signal to schedule against. It is the one exception to `scala`'s no-`var` rule, and the reason `scala.no-var` is a warning rather than an error: here the warning may stand, and nowhere else. **The rule follows the reader, not the writer:** if nothing subscribes to a cell and something reads it synchronously, it is a `var`.

Do not dress the mutation up to dodge the rule — a one-slot `js.Array[Double](0.0)` poked as `phase(0) = …`, a hand-rolled `Cell`/`Ref` wrapper with `apply`/`set`, an `AtomicReference`. Those are `var`s in costume: the mutation is still there, the reader now has to decode it, and a class plus its test suite have to be maintained to say what one keyword says.

```scala
// Bad — the write is scheduled against the running transaction; the rAF callback reading it
// this frame still sees None, gives up, and stops requesting frames
private val pendingContent = Var(Option.empty[Content])
content$.foreach(pendingContent.set)
def renderFrame(...) = pendingContent.now().foreach(draw)

// Bad — a `var` with extra steps
private val phase = js.Array[Double](0.0)
phase(0) = MapMath.frac(phase(0) + step)

// Good — the cell is imperative, so let it say so
private var pendingContent = Option.empty[Content]
content$.foreach(content => pendingContent = content)
def renderFrame(...) = pendingContent.foreach(draw)
```

## Rendering sealed-ADT signals

For a `Signal[A]` where `A` is a sealed trait / enum, never render with `child <-- signal.map { case A1 => ... case A2 => ... }`. That pattern rebuilds the entire subtree on every emission — even when the variant is unchanged — so local UI state inside the rendered branch (text-input focus, scroll position, sub-`Var` values, animation state) is lost on each upstream change. The cousin shape `child.maybe <-- signal.map { case Some(_) => ... case None => ... }` has the same defect.

Use `splitMatchOne` with one `handleType` (or `handleCase`) per variant; each branch gets a stable mount and only its own emissions retrigger its render.

```scala
// Bad — every emission of `selectionSignal` rebuilds the right-hand subtree
sealed trait Catalog
case object BuiltIn  extends Catalog
case object External extends Catalog

div(
  child <-- selectionSignal.map:
    case BuiltIn  => BuiltInCatalogPanel(state)
    case External => ExternalSchedulePanel(state)
)

// Good — each variant has a stable mount; BuiltInCatalogPanel keeps its `selected` Var across emissions
div(
  child <-- selectionSignal
    .splitMatchOne
    .handleType[BuiltIn.type](_.mapTo(BuiltInCatalogPanel(state)))
    .handleType[External.type](_.mapTo(ExternalSchedulePanel(state)))
    .toSignal
)
```

For `Signal[Option[A]]`, the same logic applies — `splitOption` keeps each branch mounted independently rather than rebuilding when the Option transitions to `None` and back.

## Laminext extensions

[Laminext](https://laminext.dev) is the project's standard companion library for handling `Option`, `Either`, and tuple-shaped emissions in Airstream chains. Reach for these before hand-rolling a `collect` / `filter` + `map` chain:

- `eventStream.collectRight` — drop `Left` and emit only the wrapped value.
- `eventStream.collectLeft` — symmetric.
- `signal.mapSome(f)` — apply `f` inside `Some`, keep `None` as `None` (no need to `.map(_.map(f))`).
- `signal.optionExists(p)` — `Signal[Boolean]` that is `true` iff the Option is `Some` and matches `p`.
- Tuple helpers — for `EventStream[(A, B)]` to filter on one component and sample the other, prefer Laminext's helpers over hand-rolled `.filter` + `.map`.

```scala
// Bad — hand-rolled collect over Either
meService.me
  .collect { case Right(profile) => profile } --> hydrateObserver

// Good — Laminext makes the intent explicit
meService.me.collectRight --> hydrateObserver
```

When in doubt, search Laminext's docs before introducing a one-off helper — there's almost always an idiom for the Option/Either/tuple shape you've encountered.

## Time and intervals — Airstream, not raw `setTimeout`/`setInterval`

For any timed UI behavior — debouncing a notification, polling, ticking counter — use Airstream's reactive primitives instead of raw `js.timers.setTimeout` / `setInterval` + a `var`. The Airstream version composes with the rest of the signal graph, cleans up automatically on unmount, and avoids the leaks and ordering bugs of imperative timers.

```scala
// Bad — raw setTimeout + Var, no cleanup on unmount; mutation outside the signal graph
val notice = Var("")
notice.set("Saved!")
js.timers.setTimeout(3000):
  notice.set("")

// Good — split the wiring: a "set" stream and a delayed "clear" stream
val saveStream: EventStream[Unit] = ???
val notice = Var("")

div(
  saveStream            --> Observer(_ => notice.set("Saved!")),
  saveStream.delay(3000) --> Observer(_ => notice.set(""))
)

// Good — periodic tick via EventStream.periodic + scanLeft, no var/setInterval
val tick: Signal[Int] =
  EventStream.periodic(1000).mapTo(1).scanLeft(0)(_ + _)
```

For state that must be mutated from a Java/JS callback API (e.g. a third-party SDK that hands you a callback rather than a stream), let the reader decide the cell (*State cells*): anything the rendering path consumes reaches it through an `EventBus` / `Var`, never as a raw `var` read at render time.

## Recover transient signal failures

A signal fed by an external source — translations fetch, API poll, websocket reconnect — can emit an error when the source fails transiently. Letting that error propagate kills every binding subscribed: `text <-- signal` and `child <-- signal` unmount when the signal errors, so the affected nodes vanish from the DOM. A single flaky request can blank out unrelated regions of the UI.

For transient failures, recover at the boundary so the binding layer never sees an untyped error:

```scala
// Bad — a fetch blip drains every text binding subscribed to translations
val translations: Signal[Translations] =
  fetchTranslationsStream.startWith(Translations.empty)

// Good — recover to last-good value; the signal never errors out
val translations: Signal[Translations] =
  fetchTranslationsStream
    .recoverIgnoreErrors
    .startWith(Translations.empty)
```

This is the legitimate "default is semantically correct" case (keep last good). Distinct from band-aiding a root-cause bug — cross-link `fail-loud` §11.

## Scala.js logging — never `JSON.stringify` Airstream/DOM values

`js.JSON.stringify(_)` throws `TypeError: cyclic object value` on objects with circular references — exactly the shape of Airstream signals carrying DOM nodes or any Laminar `Element`. Round-tripping for "deep copy" or "safe log" feeds the throw back into the redux bus / subscriber chain, and the page freezes.

```scala
// Bad — circular ref throws; the throw poisons the subscriber pipeline
def debug(value: js.Any): Unit =
  console.debug(js.JSON.parse(js.JSON.stringify(value)))

// Good — the browser console already handles cycles natively
def debug(value: js.Any): Unit =
  console.debug(value)
```

For a genuine deep clone, use `js.Dynamic.global.structuredClone(value)` — browser-native and cycle-aware.
