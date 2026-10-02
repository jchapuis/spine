---
name: skunk
description: Skunk functional PostgreSQL client for Scala/Cats Effect. Use when writing SQL queries, defining codecs, managing sessions and transactions, integrating Iron refined types with database columns, or structuring repository patterns.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['^import skunk', 'skunk\.implicits']

mechanical_checks:
  - id: skunk.session-single-in-prod
    tool: ripgrep
    severity: warning
    pattern: '\bSession\.single\b'
    paths: ['**/*.scala']
    paths_exclude: ['**/src/test/**', '**/test/**']
    message: '`Session.single` is for tests — production should use `Session.pooled`'
    references: ['skunk#sessions-and-transactions']
---

# Skunk — Functional PostgreSQL (Scala)

## Quick start
- Use `sql"..."` string interpolation to build `Fragment`s; never concatenate SQL strings.
- Compose column codecs with `*:` (tuples) and `.to[CaseClass]` for case class mapping.
- Use `Session.pooled` (as a `Resource`) for production; `Session.single` for tests.
- Wrap repositories in a trait and inject the `Session[F]` or `Resource[F, Session[F]]` pool.
- Import `io.github.iltotore.iron.skunk.given` for automatic Iron refined type codec derivation.
- Read `references/skunk.md` for codec catalogue, query/command patterns, transaction usage, and Iron integration.

## Workflow
1. Define codecs for all domain types — primitive codecs from `skunk.codec.all.*`, composed with `*:`.
2. Write SQL as `val q: Query[In, Out] = sql"SELECT ...".query(outCodec)` or `val c: Command[In] = sql"INSERT ...".command`.
3. Use `session.prepare(q).use { ps => ps.stream(input, chunkSize) }` for streaming or `ps.unique` / `ps.option` for single rows.
4. Use `session.execute(command)` for fire-and-forget DML.
5. Wrap multi-statement operations in `session.transaction.use { _ => ... }`.
6. Expose repositories as algebras (traits) with implementations that take `Session[F]` or a pool.

## Key rules
- **No string SQL concatenation** — always build queries with `sql"..."` fragments and `~` / `++` fragment composition.
- **Typed codecs for every column** — map Iron refined types via `codec.refined[Constraint]` or `codec.refined` with the correct `given` from `iron-skunk`.
- **`.to[CaseClass]`** — use `.to[T]` on codec products instead of manual tuple mapping.
- **Streaming by default** — prefer `ps.stream(input, chunkSize).compile` over `ps.all` to avoid materializing large result sets.
- **Session is not thread-safe** — never share a `Session[F]` across fibers; use `Session.pooled` and checkout one session per request/fiber.
- **Transactions bracket** — keep all statements within `session.transaction.use` inside the use block; do not pass the session out.

## Output expectations
- All SQL queries are typed: compiler rejects mismatched codec/result arities.
- Repository traits are polymorphic in `F[_]` with `Async[F]` or `MonadCancelThrow[F]`.
- Iron refined types map to their underlying DB types transparently via `given` codecs.

## References
- Load `references/skunk.md` for codec catalogue, query/command patterns, transactions, Iron integration, and PostGIS notes.
