---
name: cats
description: Cats core typeclass combinators (Scala 3) — the everyday operator vocabulary across Functor/Apply/Applicative/FlatMap/Monad/Foldable/Traverse/MonadError/Validated/OptionT/EitherT. Use when writing or refactoring code so you pick the right combinator instead of imperative iteration, nested flatMaps, manual try/catch, accumulator folds, or filter+map chains.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['^import cats(\.|\b)', 'cats\.syntax\.']

mechanical_checks:
  - id: cats.foldleft-combine-pattern
    tool: ripgrep
    severity: warning
    pattern: '\.foldLeft\([^)]*\.empty[^)]*\)\(_\s*\|\+\|\s*_\)'
    files_must_import: ['^import cats']
    message: 'foldLeft(M.empty)(_ |+| _) is just .combineAll'
    references: ['cats#foldable']
---

# Cats — Core Typeclass Combinators (Scala 3)

## Quick start
- Reach for a combinator before writing a loop, nested `if`, nested `flatMap`, or `try/catch`.
- **Sequential composition reads as `for`/`yield`.** Two or more chained/nested `flatMap`s over the same effect are a for-comprehension; a pyramid of nesting that survives one is telling you to extract a named private `def` per stage. Independent computations use `mapN`, not sequential `for` bindings.
- Import the **specific** `cats.syntax.<module>.*` modules you use (`cats.syntax.eq.*`, `cats.syntax.option.*`, `cats.syntax.traverse.*`, `cats.syntax.foldable.*`, `cats.syntax.apply.*`, …) rather than the broad `cats.syntax.all.*` wildcard — with `-Wunused:all` this keeps the import list honest.
- Prefer Cats combinators over hand-rolled equivalents — they generalise across `Option`, `Either`, `Validated`, `IO`, `F[_]` for free.
- For effect-system primitives (`bracket`, `Resource.use`, `Sync.blocking`) defer to `cats-effect-io` / `cats-effect-resource`.
- For typed domain errors, see `cats-mtl-typed-errors`.
- For streams, see `fs2`.

## Functor / Apply / Applicative

| Instead of | Use |
|---|---|
| `fa.map(_ => x)` | `fa.as(x)` |
| `fa.map(_ => ())` | `fa.void` |
| upcast via `.asInstanceOf` | `fa.widen[Super]` |
| `fa.flatMap(a => fb.map(b => f(a, b)))` for independent effects | `(fa, fb).mapN(f)` |
| `(fa, fb).mapN((_, _))` | `(fa, fb).tupled` |
| `fa.flatMap(_ => fb)` | `fa *> fb` (a.k.a. `productR`) |
| `fa.flatMap(a => fb.map(_ => a))` | `fa <* fb` (a.k.a. `productL`) |
| `if cond then action else F.unit` | `action.whenA(cond)` |
| `if !cond then action else F.unit` | `action.unlessA(cond)` |
| `flagF.flatMap(b => if b then action else F.unit)` | `action.whenM(flagF)` (also `unlessM`) |
| `List.fill(n)(action).sequence` | `action.replicateA(n)` (or `replicateA_` to discard) |

## FlatMap / Monad

| Instead of | Use |
|---|---|
| `fa.flatMap(a => log(a) *> a.pure)` | `fa.flatTap(log)` |
| `fa.flatTap(_ => sideEffect)` for pure side-effect | `fa <* sideEffect` (only if pure) |
| `boolF.flatMap(b => if b then thenF else elseF)` | `ifM(boolF)(thenF, elseF)` |
| `def loop = step.flatMap(s => if done(s) then s.pure else loop)` | `step.iterateUntil(done)` (also `iterateWhile`, `iterateForeverM`) |
| Imperative `foldLeft` over an effectful step | `xs.foldLeftM(zero)(stepF)` (also `foldM`) |

## Foldable / Reducible / Monoid

| Instead of | Use |
|---|---|
| `xs.map(f).reduce(_ |+| _)` | `xs.foldMap(f)` (requires `Monoid[B]`) |
| Manual `foldLeft(M.empty)(_ |+| _)` | `xs.combineAll` |
| `xs.map(_.toString).mkString(",")` for `Monoid[String]`-style | `xs.intercalate(",")` |
| `xs.traverse(predF).map(_.exists(identity))` | `xs.existsM(predF)` (also `forallM`) |
| `nel.map(f).reduce(_ |+| _)` (non-empty) | `nel.reduceMap(f)` (also `reduceLeftTo`) |
| `xs.foldLeft(0)((acc, x) => acc + x.weight)` | `xs.foldMap(_.weight)` (with `Monoid[Int]`) |
| `xs.map(_.distance).sum` | `xs.foldMap(_.distance)` |
| `var total = ...; xs.foreach(...)` accumulator | `xs.foldMap` / `xs.combineAll` / `xs.reduceMap` (NEL) |

### `given Monoid` once for closed-arithmetic refined types

When arithmetic is closed under a refined-type constraint (e.g. summing `Distance` values is always a valid `Distance`), define `given Monoid[T]` once on the companion. Encapsulate the lone `assume` there so call sites use `|+|`/`combineAll`/`foldMap`/`reduceMap` and never reach for the unsafe constructor.

```scala
// Distance.scala — refined type with a Monoid that hides the only `.assume`
type Distance = Distance.T
object Distance extends RefinedType[Double, GreaterEqual[0.0]]:
  given Monoid[Distance] with
    def empty: Distance = Distance.assume(0.0)
    def combine(a: Distance, b: Distance): Distance = Distance.assume(a + b)

// Call sites stay unaware of the `.assume`
val total: Distance = legs.foldMap(_.distance)
val sum: Distance   = distances.combineAll
```

This pattern keeps `assume` (the unsafe escape hatch) confined to the type's companion, where the closed-arithmetic invariant is locally obvious. Without the `Monoid`, every aggregation site re-introduces a fresh `assume` and the proof gets duplicated.

## Traverse / TraverseFilter

| Instead of | Use |
|---|---|
| `xs.map(f).sequence` | `xs.traverse(f)` |
| `xs.traverse(f).map(_.flatten)` | `xs.flatTraverse(f)` |
| `xs.traverse(f).map(_.flatten)` where `f: A => F[Option[B]]` | `xs.traverseFilter(f)` |
| `xs.flatten`-style on `List[Option[A]]` | `xs.mapFilter(identity)` / `xs.flattenOption` |
| `xs.map(asyncOp).sequence` (concurrent) | `xs.parTraverse(asyncOp)` |
| `(fa, fb).mapN(f)` (concurrent) | `(fa, fb).parMapN(f)` (also `parTupled`, `parSequence`) |
| Preserve non-emptiness | `nel.nonEmptyTraverse`, `nel.nonEmptySequence` |

## Filter / partition (no Cats needed but commonly missed)

| Instead of | Use |
|---|---|
| `xs.filter(p).map(f)` | `xs.collect { case x if p(x) => f(x) }` |
| `xs.filter(p).headOption.map(f)` | `xs.collectFirst { case x if p(x) => f(x) }` |
| `xs.filter(p).headOption` | `xs.find(p)` |
| `xs.filter(p)` and `xs.filterNot(p)` (two passes) | `xs.partitionMap` (or `partition` for same-type splits) |
| Manual `foldLeft` accumulating into a `Map[K, List[V]]` | `xs.groupBy(key)` / `groupMap(key)(value)` / `groupMapReduce(key)(value)(combine)` |

## MonadError / ApplicativeError

| Instead of | Use |
|---|---|
| `try { effect } catch { case e: T => ... }` | `effect.attempt` (returns `F[Either[Throwable, A]]`) |
| `effect.attempt.map(_.toOption)` for `Option`-style | `effect.attemptT.toOption.value` |
| `effect.attempt.flatTap { case Left(e) => log(e); case Right(_) => F.unit }` | `effect.attemptTap(eitherToF)` |
| `try/catch` recovering with another effect | `effect.handleErrorWith(recover)` (also `handleError`, `recover`, `recoverWith`) |
| `effect.attempt.flatMap { case Left(e) => onErr(e); case Right(a) => onOk(a) }` | `effect.redeemWith(onErr, onOk)` (also `redeem` for non-effectful) |
| `effect.flatMap(a => if p(a) then a.pure else F.raiseError(err(a)))` | `effect.ensure(err)(p)` (also `ensureOr`) |
| Translate error type | `effect.adaptError { case e: Old => New(e) }` |

For typed domain errors with `Raise[F, E]` / `Handle[F, E]` / `allow`/`rescue`, see **`cats-mtl-typed-errors`**.

## Validated / Either / Option

| Instead of | Use |
|---|---|
| Short-circuit `Either` for multi-field validation | `(field1V, field2V, …).mapN(Record.apply)` with `ValidatedNec`/`ValidatedNel` |
| Construct `Validated` from a predicate | `Validated.condNec(cond, value, error)` |
| Construct success / failure | `value.validNec` / `error.invalidNec` |
| `if cond then Right(x) else Left(err)` | `Either.cond(cond, x, err)` |
| `opt.toRight(err)` | already idiomatic |
| `Option(x).filter(p)` for `if cond then Some(x) else None` | `Option.when(cond)(x)` (also `Option.unless`) |
| Nested `flatMap` over `F[Option[A]]` | `OptionT(fOpt).flatMap(...).value` |
| Nested `flatMap` over `F[Either[E, A]]` | `EitherT(fEither).flatMap(...).value` |
| Lift pure `Option`/`Either` into `OptionT`/`EitherT` | `OptionT.fromOption[F](opt)`, `EitherT.fromEither[F](e)` |
| Lift effect into transformer | `OptionT.liftF(fa)`, `EitherT.liftF(fa)` |

## Out of scope (sibling skills)

| Pattern | Skill |
|---|---|
| `MonadCancel.guarantee`, `bracket`, `bracketCase`, `Resource.use`/`make` | `cats-effect-resource` |
| `Async.fromFuture`, `Sync.blocking`/`interruptible`, `Dispatcher`, fibers | `cats-effect-io` |
| `Raise[F, E]`, `Handle[F, E]`, `allow`/`rescue` | `cats-mtl-typed-errors` |
| `Stream.eval`, `groupWithin`, `parEvalMap`, streaming pipelines | `fs2` |
| Nested `.copy(...)` updates → optics | `monocle` |

## Decision rule
Before writing imperative iteration, a manual `foldLeft`, a try/catch, or a nested `flatMap`, scan the table above. The combinator usually exists; the question is just which row applies.

## References
- Cats docs: https://typelevel.org/cats/
- Alexandru Nedelcu's blog (typelevel-style FP in practice): https://alexn.org/
- Underscore "Scala with Cats" (free book): https://underscore.io/books/scala-with-cats/
