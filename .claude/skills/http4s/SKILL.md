---
name: http4s
description: http4s functional HTTP server for Scala/Cats Effect. Use when defining HttpRoutes, wiring Tapir-interpreted routes, applying middleware, configuring a Netty/Ember server, or composing multiple route algebras.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['org\.http4s']
---

# http4s — Functional HTTP Server (Scala)

## Quick start
- In Tapir-first projects, define routes via `Http4sServerInterpreter[F]().toRoutes(serverEndpoints)` rather than raw `HttpRoutes.of`.
- Combine route groups with `Router("/api" -> apiRoutes, "/" -> assetRoutes)`.
- Convert to `HttpApp[F]` with `.orNotFound` before passing to the server builder.
- Use `EmberServerBuilder` (default) or `NettyServerBuilder` as a `Resource[F, Server]`.
- Apply middleware as wrapping functions: `Logger.httpRoutes[F](...)(routes)`.
- Read `references/http4s.md` for routing DSL, middleware catalogue, server setup, and request/response handling.

## Workflow
1. Define service algebras; inject via `using` parameters.
2. Create `HttpRoutes[F]` — either from Tapir `serverEndpoints` or raw `HttpRoutes.of[F] { ... }`.
3. Combine with `Router` and apply middleware.
4. Lift to `HttpApp[F]` with `.orNotFound`.
5. Wrap the server in a `Resource[F, Server]` and use it from `IOApp`/`ResourceApp`.

## Key rules
- **Tapir-first**: prefer Tapir endpoint definitions over raw `HttpRoutes.of` for all API routes; use raw DSL only for asset serving, health checks, or WebSocket routes.
- **`orNotFound`** — always convert `HttpRoutes[F]` to `HttpApp[F]` at the outermost composition point, not inside sub-modules.
- **Middleware order matters** — outer middleware wraps inner; apply `Logger` outermost for full request/response logging.
- **`req.as[T]`** returns `F[T]`; always decode body inside the effect, not before.
- **Path variables**: use extractors (`IntVar`, `LongVar`, `UUIDVar`) or Tapir path segments — never parse manually.
- **Server is a `Resource`** — never start the server outside a resource; compose it with other resources in `IOApp.run`.

## Output expectations
- Routes are typed `HttpRoutes[F]` where `F[_]: Async`.
- Server lifecycle is managed by `Resource[F, Server]`; no manual start/stop.
- All external routes have corresponding Tapir endpoint definitions for type-safe client generation.

## References
- Load `references/http4s.md` for routing DSL, Tapir integration, middleware, and server builder examples.
