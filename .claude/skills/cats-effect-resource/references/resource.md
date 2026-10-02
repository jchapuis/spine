# Cats Effect Resource (Scala) - Practical Guide

Sources:
- https://typelevel.org/cats-effect/docs/std/resource
- https://github.com/typelevel/cats-effect/blob/series/3.x/kernel/shared/src/main/scala/cats/effect/kernel/Resource.scala

## Table of Contents
- [Core model](#core-model)
- [Core APIs](#core-apis)
- [When to use Resource](#when-to-use-resource)
- [Patterns](#patterns)
- [Cancelation and error behavior](#cancelation-and-error-behavior)
- [Interop and blocking](#interop-and-blocking)
- [Checklist](#checklist)

## Core model
- `Resource[F, A]` encodes acquisition and release with a `use` phase.
- Release runs on success, error, or cancelation.
- Acquisition and finalizers are sequenced and run in a controlled scope; release is LIFO.

## Core APIs
- `Resource.make(acquire)(release)` for custom lifecycle.
- `Resource.fromAutoCloseable` for `AutoCloseable` lifecycles.
- `Resource.eval` to lift an effect into a resource.
- `.use` to run the resource and ensure release.
- `map`, `flatMap`, `mapN`, `parMapN`, `parZip` to compose resources.

## When to use Resource
- You need safe cleanup under cancelation.
- You need to compose resources and guarantee LIFO release.
- You want an API that makes lifecycle explicit and testable.

## Patterns

### 1) Resource constructors
Prefer functions that return `Resource[F, A]`:

```scala
import cats.effect.{Resource, Sync}

final class UserProcessor {
  def start(): Unit = ()
  def shutdown(): Unit = ()
}

def userProcessor[F[_]: Sync]: Resource[F, UserProcessor] =
  Resource.make(Sync[F].delay { new UserProcessor().tap(_.start()) })(p =>
    Sync[F].delay(p.shutdown())
  )
```

### 2) Composing resources

```scala
import cats.effect.{Resource, Sync}
import cats.syntax.apply._

final class DataSource { def connect(): Unit = (); def close(): Unit = () }
final class Service(ds: DataSource, up: UserProcessor)

def dataSource[F[_]: Sync]: Resource[F, DataSource] =
  Resource.make(Sync[F].delay { new DataSource().tap(_.connect()) })(ds =>
    Sync[F].delay(ds.close())
  )

def service[F[_]: Sync]: Resource[F, Service] =
  (dataSource[F], userProcessor[F]).mapN(new Service(_, _))
```

### 3) Parallel acquisition

```scala
import cats.effect.{Resource, Sync}
import cats.syntax.parallel._

def servicePar[F[_]: Sync]: Resource[F, Service] =
  (dataSource[F], userProcessor[F]).parMapN(new Service(_, _))
```

### 4) File input stream

```scala
import cats.effect.{IO, Resource}

import java.io.FileInputStream

def inputStream(path: String): Resource[IO, FileInputStream] =
  Resource.fromAutoCloseable(IO.blocking(new FileInputStream(path)))
```

### 5) Database pool + per-connection resource

```scala
import cats.effect.{Resource, Sync}

import javax.sql.DataSource

def pool[F[_]: Sync]: Resource[F, DataSource] = ???

def connection[F[_]: Sync](ds: DataSource): Resource[F, java.sql.Connection] =
  Resource.make(Sync[F].blocking(ds.getConnection))(c =>
    Sync[F].blocking(c.close())
  )
```

### 6) Acquire in a loop
Use `Resource.make` per element and compose with `traverse`/`parTraverse`:

```scala
import cats.effect.{Resource, Sync}
import cats.syntax.traverse._

def acquireOne[F[_]: Sync](id: String): Resource[F, Handle] = ???

def acquireAll[F[_]: Sync](ids: List[String]): Resource[F, List[Handle]] =
  ids.traverse(acquireOne[F])
```

### 7) Multi-step release isolation
A single recovery wrapping two fallible release calls looks safe but isn't: if the first throws, the second never runs and you leak the underlying handle (file lock, DB connection, native buffer). Each fallible release step needs its own recovery boundary.

```scala
// Bad — single recovery wraps both effects; if iterator.close throws, store.dispose is skipped → leaked handle
val release: IO[Unit] =
  (IO.blocking(iterator.close()) >> IO.blocking(store.dispose()))
    .recoverWith { case t => Logger[IO].warn(t)("cleanup failed") }

// Good — each step has its own recovery; both run regardless of the other's outcome
val release: IO[Unit] =
  IO.blocking(iterator.close())
    .recoverWith { case t => Logger[IO].warn(t)("iterator close failed") } >>
  IO.blocking(store.dispose())
    .recoverWith { case t => Logger[IO].warn(t)("store dispose failed") }
```

The same applies to `bracket` finalizers and `Resource.make` release functions: keep each release step in its own effect, sequence them with `>>` (or `guarantee` for ordering safety), and recover each independently.

## Cancelation and error behavior
- Finalizers run on success, error, or cancelation.
- If finalizers can fail, decide whether to log, suppress, or raise secondary errors.
- Keep finalizers idempotent and minimal to avoid cascading failures during release.
- When a release performs **multiple fallible steps**, wrap each in its own effect with its own recovery — see Pattern 7 above.

## Interop and blocking
- Wrap blocking acquisition or release in `blocking` to avoid compute starvation.
- Prefer `Resource.fromAutoCloseable` for Java interop; use `make` for custom release.
- If the API supports cooperative cancellation, combine it with `Resource` to ensure cleanup.

## Checklist
- Expose `Resource[F, A]` in public constructors.
- Keep release idempotent and tolerant of partial failures.
- Use `parMapN` only for independent resources.
- Avoid calling `.use` except at lifecycle boundaries.
- Use `IO.blocking`/`Sync[F].blocking` for blocking JVM APIs.
