---
name: cats-effect-resource
description: Scala resource lifecycle management with Cats Effect `Resource` and `IO`. Use when defining safe acquisition/release, composing resources (including parallel acquisition), or designing resource-safe APIs and cancellation behavior for files, streams, pools, clients, and background fibers.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['cats\.effect\.(kernel\.)?Resource', 'cats\.effect\.std\.(Hotswap|Dispatcher)']
---

# Cats Effect Resource (Scala)

## Quick start
- Model each resource with `Resource.make` or `Resource.fromAutoCloseable` and keep release idempotent.
- Compose resources with `flatMap`, `mapN`, `parMapN`, or helper constructors; expose `Resource[F, A]` from APIs.
- Use `Resource` at lifecycle boundaries and call `.use` only at the program edges.
- **Isolate multi-step release**: never combine multiple fallible cleanup calls in a single `IO.blocking { stepA; stepB }` — if `stepA` throws, `stepB` is skipped and you leak. Chain them with `>>` or `guarantee` so each step is independently recovered.
- Read `references/resource.md` for patterns, best practices, and API notes.

## Workflow
1. Identify acquisition, use, and release steps; decide if acquisition is blocking.
2. Implement a `Resource[F, A]` constructor using the smallest needed typeclass.
3. Compose resources into higher-level resources and keep finalizers minimal.
4. Decide how cancelation and errors should influence release logic.
5. Run with `.use` at the boundary (IOApp, service startup) and avoid leaking raw `A`.

## Usage guidance
- Prefer `Resource` over `try/finally` or `bracket` when composition and cancelation safety matter.
- Use `IO.blocking` (or `Sync[F].blocking`) for acquisition and release when calling blocking JVM APIs.
- For background fibers, use `Resource` or `Supervisor` to ensure cleanup on cancelation.

## References
- Load `references/resource.md` for API details, patterns, and examples.
