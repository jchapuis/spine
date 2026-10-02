# fs2 — Functional Streams Reference (Scala)

Sources:
- https://github.com/typelevel/fs2/blob/main/site/guide.md
- https://github.com/typelevel/fs2/blob/main/site/concurrency-primitives.md

## Table of Contents
- [Core model](#core-model)
- [Constructors](#constructors)
- [Transformations](#transformations)
- [Effectful operators](#effectful-operators)
- [Batching with groupWithin](#batching-with-groupwithin)
- [Parallelism](#parallelism)
- [Pipes and composition](#pipes-and-composition)
- [Compilation](#compilation)
- [Resource integration](#resource-integration)
- [Concurrency primitives](#concurrency-primitives)
- [Interruption](#interruption)
- [Error handling](#error-handling)

---

## Core model

- `Stream[F, A]` — a potentially infinite, effectful sequence of values of type `A` in effect `F`.
- `Pipe[F, A, B]` — alias for `Stream[F, A] => Stream[F, B]`; reusable transformation.
- `Pull[F, A, R]` — lower-level; prefer `Stream` combinators when possible.
- Streams are lazy: nothing runs until `.compile` is called.

---

## Constructors

```scala
import fs2.Stream
import cats.effect.IO

// Pure:
Stream.empty[IO]
Stream.emit(1)                     // single element
Stream.emits(List(1, 2, 3))        // from collection
Stream.range(0, 100)               // [0, 100)
Stream.constant(42)                // infinite: 42, 42, 42, ...
Stream.iterate(0)(_ + 1)           // infinite: 0, 1, 2, 3, ...

// Effectful:
Stream.eval(IO.println("hi"))      // Stream[IO, Unit]
Stream.evals(IO(List(1, 2, 3)))    // Stream[IO, Int] from F[List[A]]
Stream.exec(IO.println("hi"))      // Stream[IO, Nothing] — effect only, no output

// Time-based:
Stream.awakeEvery[IO](1.second)    // Stream[IO, FiniteDuration] — ticks every 1s
Stream.sleep[IO](500.millis)       // Stream[IO, Nothing] — delay then empty
```

---

## Transformations

```scala
val s = Stream.emits[IO, Int](1 to 10)

s.map(_ * 2)
s.filter(_ % 2 == 0)
s.flatMap(n => Stream.emits(List.fill(n)(n)))
s.take(5)
s.drop(3)
s.takeWhile(_ < 8)
s.dropWhile(_ < 3)
s.zipWithIndex                     // Stream[IO, (Int, Long)]
s.scan(0)(_ + _)                   // running sum
s.chunkN(4)                        // Stream[IO, Chunk[Int]] — groups of 4
s.unchunks                         // flattens Stream[IO, Chunk[A]] → Stream[IO, A]
```

---

## Effectful operators

```scala
// Sequential:
s.evalMap(n => IO.println(n) *> IO.pure(n * 2))   // transforms and emits
s.evalTap(n => IO.println(n))                       // side-effect, passes through
s.evalFilter(n => IO.pure(n > 5))                   // effectful filter

// mapChunks — transforms entire Chunk at once (no effect):
s.mapChunks(chunk => chunk.map(_ + 1))
```

---

## Batching with groupWithin

The primary pattern for transparent batching in these projects:

```scala
import scala.concurrent.duration.*

// Emit a Chunk whenever 100 items accumulate OR 500ms have passed:
val batched: Stream[IO, Chunk[Int]] =
  inputStream.groupWithin(100, 500.millis)

// Process each batch:
batched.evalMap { chunk =>
  db.insertBatch(chunk.toList)
}

// Full pipeline:
eventSource
  .groupWithin(256, 200.millis)
  .evalMap(chunk => repo.upsertMany(chunk.toList))
  .compile
  .drain
```

---

## Parallelism

```scala
// parEvalMap — parallel effects, order preserved:
s.parEvalMap(8)(n => fetchRemote(n))

// parEvalMapUnordered — parallel, emit as completed (better throughput):
s.parEvalMapUnordered(8)(n => fetchRemote(n))

// parJoin — run N inner streams concurrently, interleave output:
val streams: Stream[IO, Stream[IO, Int]] = Stream.emits(List(s1, s2, s3))
streams.parJoin(3)

// merge — two streams interleaved (terminates when both end):
s1.merge(s2)

// concurrently — run a background stream, discard its output:
dataStream.concurrently(heartbeatStream)
```

---

## Pipes and composition

```scala
import fs2.Pipe

// Define reusable pipes:
def logElements[F[_]: cats.effect.std.Console, A]: Pipe[F, A, A] =
  _.evalTap(a => Console[F].println(a))

def batchInsert(repo: Repo): Pipe[IO, Event, Nothing] =
  _.groupWithin(100, 500.millis)
   .evalMap(chunk => repo.insertAll(chunk.toList))
   .drain

// Compose with .through:
eventStream
  .through(logElements)
  .through(batchInsert(repo))
  .compile
  .drain

// andThen for pipe composition:
val pipeline: Pipe[IO, Event, Nothing] =
  logElements[IO, Event].andThen(batchInsert(repo))
```

---

## Compilation

```scala
s.compile.toList          // IO[List[A]]
s.compile.toVector        // IO[Vector[A]]
s.compile.drain           // IO[Unit] — run for effects only
s.compile.last            // IO[Option[A]] — last emitted element
s.compile.count           // IO[Long]
s.compile.fold(z)(f)      // IO[B] — reduce
s.compile.resource        // Resource[IO, List[A]] — run within resource scope
```

---

## Resource integration

```scala
import cats.effect.Resource

val res: Resource[IO, Connection] = ???

// Acquire once, emit elements, then release:
Stream.resource(res).flatMap { conn =>
  Stream.eval(conn.query("SELECT ..."))
}

// Per-element resource (acquire/release for each element):
s.flatMap { item =>
  Stream.resource(openFile(item)).evalMap(readContents)
}

// compile.resource — the stream's finalizers run when the Resource is released:
val r: Resource[IO, List[Int]] = s.compile.resource.toList
```

---

## Concurrency primitives

```scala
import fs2.concurrent.{Channel, SignallingRef, Topic}

// Channel — multi-producer, single-consumer queue:
Channel.unbounded[IO, Event].flatMap { ch =>
  val producer = Stream.repeatEval(nextEvent).evalMap(ch.send).void
  val consumer = ch.stream.evalMap(process)
  producer.merge(consumer)
}

// SignallingRef — observable Ref; stream emits on every change:
SignallingRef[IO, Int](0).flatMap { ref =>
  val updater = Stream.repeatEval(ref.update(_ + 1)).metered(1.second)
  val reader  = ref.discrete.evalMap(n => IO.println(s"value: $n"))
  updater.merge(reader)
}

// Topic — broadcast; multiple subscribers each receive all published values:
Topic[IO, Event].flatMap { topic =>
  val publisher  = eventStream.through(topic.publish)
  val subscriber = topic.subscribe(maxQueued = 100).evalMap(handle)
  publisher.concurrently(subscriber)
}
```

---

## Interruption

```scala
import scala.concurrent.duration.*

// Time-bounded:
s.interruptAfter(30.seconds)

// Signal-based (interrupt when signal becomes true):
SignallingRef[IO, Boolean](false).flatMap { stop =>
  s.interruptWhen(stop)
   .concurrently(
     Stream.sleep[IO](30.seconds) >> Stream.exec(stop.set(true))
   )
}

// takeWhile for pure condition:
s.takeWhile(n => n < 100)
```

---

## Error handling

```scala
// Recover from error, emit fallback value:
s.handleErrorWith(err => Stream.eval(IO.println(s"error: $err")) >> Stream.empty)

// Attempt — convert errors to Either:
s.attempt  // Stream[IO, Either[Throwable, A]]

// Retry individual effects inside evalMap:
import cats.effect.std.RetryPolicy
s.evalMap(item =>
  retry(process(item), policy)
)

// Last-resort: log and drain remaining stream:
s.onError { err =>
  Stream.exec(logger.error("stream failed", err))
}
```
