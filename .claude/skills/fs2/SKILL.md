---
name: fs2
description: fs2 functional streaming for Scala/Cats Effect. Use when building data pipelines, transparent batching with groupWithin, parallel evaluation, resource-safe stream composition, or integrating streams with Cats Effect concurrency primitives.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['^import fs2', 'fs2\.Stream']

mechanical_checks:
  - id: fs2.no-unbounded-par-eval-map
    tool: ripgrep
    severity: error
    pattern: '\.parEvalMap(Unordered)?Unbounded\b'
    files_must_import: ['^import fs2']
    message: 'Use `.parEvalMap(n)` with an explicit bound — never the unbounded variants in production pipelines'
    references: ['fs2#parallelism']
---

# fs2 — Functional Streams (Scala)

## Quick start
- Model data pipelines as `Stream[F, A]` values; compile at the outermost boundary.
- Use `Stream.eval(fa)` to lift a single effect, `Stream.emits(list)` for a collection.
- Use `.evalMap` for sequential effectful transformation; `.parEvalMap(n)` for bounded parallelism.
- Use `.groupWithin(maxSize, maxDuration)` for transparent batching (collect chunks bounded by size or time).
- Integrate with `Resource` via `Stream.resource(res)` or `.compile.resource`.
- Read `references/fs2.md` for stream constructors, combinators, concurrency primitives, and patterns.

## Workflow
1. Construct the source stream (`Stream.emits`, `Stream.eval`, `Stream.resource`, `Stream.awakeEvery`).
2. Transform with pure operators (`.map`, `.filter`, `.flatMap`) and effectful operators (`.evalMap`, `.evalTap`).
3. Apply concurrency where needed (`.parEvalMap`, `.parJoin`, `.merge`).
4. Pipe through reusable `Pipe[F, A, B]` functions with `.through(pipe)`.
5. Compile: `.compile.toList`, `.compile.drain`, `.compile.resource`, or `.compile.last`.

## Key rules
- **Compile once at the top** — never call `.compile.drain` in the middle of a pipeline; compose streams fully before compiling.
- **`.groupWithin` for batching** — the idiomatic way to batch items bounded by count or time; avoid manual `Chunk` management.
- **`parEvalMap` preserves order** — use `.parEvalMapUnordered` when order does not matter and throughput matters more.
- **Resource-safe** — wrap `Stream.resource(r)` to guarantee finalization even on cancellation; never acquire/release manually inside a stream.
- **`Channel` for fan-in** — use `fs2.concurrent.Channel` when multiple producers write to one consumer; prefer over `Queue` in new code.
- **`SignallingRef` for state** — use for values that need to be observed as a stream and modified concurrently.
- **Do not block inside `evalMap`** — wrap blocking calls with `IO.blocking` before lifting into the stream.

## Output expectations
- Pipelines are expressed as composed `Stream[F, A]` values.
- Parallelism is always bounded (`parEvalMap(n)`, `parJoin(n)`) — never unbounded unless explicitly required.
- All resource-lifecycle concerns are handled by `Stream.resource` or `compile.resource`, never by manual finalization.

## References
- Load `references/fs2.md` for constructors, pipes, concurrency primitives, groupWithin batching, and integration patterns.
