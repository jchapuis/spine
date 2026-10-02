---
name: cats-effect-io
description: Scala functional programming with Cats Effect IO and typeclasses. Use for wrapping side effects, modeling purity, choosing Sync/Async/Temporal/Concurrent, handling blocking I/O, and composing resources, fibers, and concurrency safely.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']

mechanical_checks:
  - id: cats-effect-io.effectful-time
    tool: ripgrep
    severity: error
    pattern: '\b(Instant\.now|System\.(currentTimeMillis|nanoTime))\b'
    paths: ['**/*.scala']
    paths_exclude: ['**/src/test/**', '**/src/it/**']
    message: 'Reading the clock is an effect — use `IO.realTimeInstant` / `Clock[F]` (or the project''s Clock service in frontend code), never `Instant.now` / `System.currentTimeMillis`'
    references: ['cats-effect-io#side-effect-rules-apply-to-io-syncio-and-to-f_-syncasync']
  - id: cats-effect-io.no-unsafe-run
    tool: ripgrep
    severity: error
    pattern: '\bunsafeRun(Sync|AndForget|Async|Timed|ToFuture)\b'
    paths: ['**/*.scala']
    paths_exclude: ['**/src/main/scala/**/IOApp*', '**/main/**Main.scala']
    message: 'No unsafeRun* outside the IOApp boundary — return IO[A] and let the runtime drive'
    references: ['cats-effect-io#composition']
  - id: cats-effect-io.no-unsafe-global-import
    tool: ripgrep
    severity: error
    pattern: '^import\s+cats\.effect\.unsafe\.implicits\.global\b'
    paths: ['**/*.scala']
    message: 'No `cats.effect.unsafe.implicits.global` — IOApp/ResourceApp provides the runtime'
    references: ['cats-effect-io#composition']
---

# Cats Effect IO (Scala)

## Quick start
- Treat every side effect as an effect value: return `IO[A]`, `SyncIO[A]`, or `F[A]` with `F[_]: Sync`/`Async`/`Temporal` as needed.
- Wrap Java blocking calls with `IO.blocking` or `IO.interruptible` (or `Sync[F].blocking`/`interruptible`).
- Use `Resource` to acquire/release resources and `IOApp` for program entry points.
- Prefer structured concurrency (`parTraverse`, `parMapN`, `background`, `Supervisor`) over manual fiber management.
- Do not use `unsafeRun*` (`unsafeRunSync`, `unsafeRunAndForget`, etc.) in app code or tests; for interop with non-Cats-Effect callback APIs, use `Dispatcher`.
- Read `references/cats-effect-io.md` for concepts, recipes, and FAQ guidance.
- For deeper `Resource` guidance, use the `cats-effect-resource` skill.

## Workflow
1. Classify side effects and choose the effect type: `IO` directly or polymorphic `F[_]` with the smallest required Cats Effect typeclass (`Sync`, `Async`, `Temporal`, `Concurrent`).
2. Wrap side-effectful code using `IO(...)`, `IO.blocking`, `IO.interruptible`, or `IO.async` (or their `Sync`/`Async` equivalents).
3. Manage resources with `Resource` or `bracket` and keep acquisition/release inside effects.
4. Compose effects with `flatMap`/for-comprehensions and collection combinators (`traverse`, `parTraverse`).
5. Use concurrency primitives (`Ref`, `Deferred`, `Queue`, `Semaphore`, `Supervisor`) and structured concurrency to avoid fiber leaks.
6. Keep effect execution at boundaries (`IOApp`, framework runtime); for callback-style interop, bridge with `Dispatcher`.

## Side-effect rules (apply to `IO`, `SyncIO`, and to `F[_]: Sync/Async`)
- All side-effectful functions must return results wrapped in `IO` (or `F[_]` with Cats Effect typeclasses).
- Side-effects include all non-determinism (call sites are not referentially transparent):
  - Any I/O (files, sockets, console, databases).
  - `Instant.now()`, `Random.nextInt()`.
  - Any read from shared mutable state (the read itself is the side effect).
  - Returning mutable data structures (for example, `Array[Int]`).

## Blocking I/O rules
- Java blocking methods must be wrapped in `IO.blocking` or `IO.interruptible` (or `Sync[F].blocking`/`interruptible`) so they run on the blocking pool.
- Prefer `IO.interruptible` for methods that may throw `InterruptedException` or `IOException`, but not for resource disposal.
- Use `IO.blocking` for cleanup/disposal (`Closeable#close`, `AutoCloseable#close`).

## Output expectations
- Make side effects explicit in signatures (`IO`/`SyncIO` or `F[_]: Sync/Async`); the guidance here applies equally to concrete `IO` and polymorphic `F[_]`.
- Use the smallest typeclass constraint that supports the needed operations.
- Keep effects as values; do not execute effects in constructors or top-level vals.

## Execution and test rules
- `unsafeRun*` is forbidden in production and test code, including `import cats.effect.unsafe.implicits.global`.
- If interop requires running effects from non-Cats-Effect callbacks, use `Dispatcher`.
- Prefer effect-native test styles (return `IO[Assertion]`/`F[Assertion]`) instead of manually running effects.
- Avoid `IO.sleep`/`Thread.sleep` in unit tests unless using virtual time with `TestControl`.

## References
- Load `references/cats-effect-io.md` for documentation summary, patterns, and samples.
- Use the `cats-effect-resource` skill for Resource-specific workflows and patterns.
