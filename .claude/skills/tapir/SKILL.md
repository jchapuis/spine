---
name: tapir
description: Tapir type-safe HTTP endpoint definitions for Scala. Use when defining REST endpoints, wiring server logic with http4s, generating STTP client stubs, deriving JSON schemas, or handling typed errors and security flows.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['sttp\.tapir']
---

# Tapir — Type-Safe HTTP Endpoints (Scala)

## Quick start
- Define endpoints once in the `shared/` cross-compiled module; import them in both `server/` and `client/`.
- Start with `endpoint` (or `infallibleEndpoint`) and chain `.in(...)`, `.out(...)`, `.errorOut(...)`.
- Use `PublicEndpoint[I, E, O, R]` for unauthenticated endpoints.
- Import `sttp.tapir.*`, `sttp.tapir.generic.auto.*`, and the JSON codec module (`sttp.tapir.json.circe.*` or `sttp.tapir.json.upickle.*`).
- Wire to http4s: `Http4sServerInterpreter[F]().toRoutes(endpoint.serverLogic(handler))`.
- Wire to STTP client: `SttpClientInterpreter().toRequest(endpoint, Some(baseUri)).apply(input)`.
- Read `references/tapir.md` for endpoint anatomy, schema derivation, error patterns, and security.

## Workflow
1. Define the endpoint shape in `shared/` — inputs, outputs, error outputs, method, path.
2. Derive `Schema[T]` for all request/response types (auto-derivation via `generic.auto.*`).
3. Provide JSON codecs for all types (Circe `Encoder`/`Decoder` or Upickle `ReadWriter`).
4. In `server/`, attach server logic returning `F[Either[E, O]]`.
5. In `client/`, use `SttpClientInterpreter` or `Http4sClientInterpreter` to get a typed request builder.
6. For authenticated endpoints, chain `.serverSecurityLogic(secLogic)` → `.serverLogic(mainLogic)`.

## Key rules
- **One definition, two uses**: the same `endpoint` value drives both server routes and client calls.
- **Always `.errorOut`**: provide explicit error outputs — never rely on untyped 500 responses.
- **`mapTo[CaseClass]`**: use `.mapTo` to map tuples to case classes for both input and output codecs (bidirectional).
- **Schema derivation**: import `sttp.tapir.generic.auto.*` for automatic Schema derivation; use `Schema.derived` explicitly for Scala 3 mirror-based derivation.
- **Iron integration**: import `sttp.tapir.codec.iron.*` to get `Schema` and `Codec` for Iron refined types automatically.
- **Status codes**: defaults are 200/400; override with `.out(statusCode(StatusCode.Created))` or `.out(statusCode)` for dynamic codes.
- **infallibleEndpoint**: use when error channel is `Unit` / no errors can occur.

## Output expectations
- Endpoint definitions live in `shared/` and are cross-compiled for JVM and JS.
- Server logic is attached in `server/`; client logic in `client/`.
- All API types have `Schema` instances in scope before `serverLogic` or `toRequest` is called.

## References
- Load `references/tapir.md` for endpoint anatomy, schema, JSON, security, and client patterns.
