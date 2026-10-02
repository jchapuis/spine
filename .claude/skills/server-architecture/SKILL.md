---
name: server-architecture
description: Layered server architecture for Scala/Cats Effect projects (Services, Algebras, Repositories, Logic traits). Use when adding a new server component, deciding where orchestration logic belongs, spotting a service-to-service dependency that should be an algebra, or wiring a Tapir logic trait.

triggers:
  - file_patterns:
      - '**/server/**/*.scala'
      - '**/infra/**/*.scala'
      - '**/*Service.scala'
      - '**/*Algebra.scala'
      - '**/*Repository.scala'
      - '**/*Logic.scala'
      - '**/*Config*.scala'
      - '**/*.conf'
  - file_patterns: ['**/*.scala']
    imports: ['com\.raquo\.laminar']

mechanical_checks:
  - id: server-architecture.frontend-imports-api
    tool: ripgrep
    severity: warning
    pattern: '^import\s+(?!com\.raquo)\S+\.api\.'
    files_must_import: ['com\.raquo\.laminar']
    message: 'Frontend (Laminar) code must not import from the server-side `api` package — wire-shape DTOs belong in `shared/model`'
    references: ['server-architecture#dto-ownership']
---

# Server Architecture — Layered Design (Scala / Cats Effect)

## Quick start
- **Service** — thin gateway to one external system (HTTP client, physics engine, DB driver). Trait with one impl per backend. No orchestration, no service dependencies.
- **Algebra** — composable business logic and orchestration. May depend on other algebras, services, repositories, and config. Primary unit-test target.
- **Repository** — typed access to persistence. Depends only on the DB session and model types. No algebra or service dependencies.
- **Logic trait** — wires Tapir endpoint definitions to implementations via `endpoint.serverLogic`. No inline business logic; all work delegated via `using` parameters.
- **Hard rule**: if a service depends on another service, the orchestrating code belongs in an algebra.
- **Always extract a trait** for any algebra/service that is non-trivial — the trait is the seam your tests target. `object Foo` with concrete logic is a smell; convert to `trait Foo` + `class FooImpl(using config)` so tests can substitute fixtures via `given` injection.
- **Configuration** — every config class lives in the `config` subpackage, sourced from `application.conf` via `ConfigReader`. No in-code defaults, no per-test ad-hoc construction. Tuning constants hardcoded in algebra/extension code (a distance threshold, a result cap) belong in a config class sourced from `application.conf`; the `config` subpackage holds config classes only, not domain concepts.
- **Domain types live in `model/`** once 2+ callers need them — not next to the producing algebra.
- **Wrap external errors at the algebra boundary** — algebras translate raw client/HTTP/SDK errors into the project's typed error ADT before tapir logic sees them.
- **Scope error and detail types to where they're used** — a narrow error or helper type used by exactly one class belongs in that class's companion; promote it to `model/` only once 2+ callers need it. Don't park helpers in a `private[pkg]` namespace as a catch-all — see `scala#no-qualified-private-namespaces`.
- **DTO ownership** — wire-shape case classes (HTTP request/response payloads, catalog entries shared between server and frontend) live in `shared/model`, **never** in the `api` package. The `api` package contains only Tapir endpoint definitions, which reference the model types. Frontend code imports from `shared/model` only — never from `api.*`. This stops internal status enums from accidentally leaking into UI code and stops the api package from becoming a second domain model.

## Workflow
1. **New external capability** → add a `Service` trait + one impl; inject only config and the external client.
2. **New business rule or orchestration** → add or extend an `Algebra`; inject services, repositories, and other algebras it needs.
3. **New persistence query** → add a method to the relevant `Repository` trait and its SQL impl.
4. **New HTTP endpoint** → add the Tapir endpoint definition, then wire it in a `Logic` trait with `endpoint.serverLogic`; inject algebras/services/repositories via named `using` parameters.
5. **Service calls another service?** → extract the orchestration into a new or existing algebra; make the service a one-line delegate.

## Key rules

**Dependency matrix**

| Layer | May depend on |
|-------|---------------|
| Service | Config, HTTP clients, external libraries |
| Algebra | Other algebras, services, repositories, config |
| Repository | DB session, model types |
| Logic trait | Algebras, services, repositories |

**Services**
- Defined as a `trait` with one implementation per external system.
- Contains minimal logic — routing through the external API only.
- **Never depend on other services.** Service-to-service wiring is always wrong; move it to an algebra.

**Algebras**
- Defined as a `trait + companion object` for pure algebras with no injected dependencies, or as a `class` when config/dependencies are injected (constructor or `using`). Injected immutable config alone doesn't make an algebra "stateful" — the class form exists to give the helper bundle shared scope. (For non-trivial algebras the trait-first rule below still applies: the class is the `XxxImpl` behind the trait, not a replacement for it.) It's also the right move whenever a companion's `private def` helpers all thread the same injected config or dependency — see `scala#class-scope-for-helper-bundles` for the general form of the refactor.
- All non-trivial business logic lives here.
- **Trait-first for testability** — every non-trivial algebra is a `trait` with a `class XxxImpl(using config)` (or a `class Xxx(using config)`) implementation. Tests substitute the trait via fixture-provided `given` instances; mocking concrete `object`s is far more painful and tends to leak the implementation.
- **Small impls stay anonymous.** A named `XxxImpl` class earns its existence only when it needs class scope — a helper bundle, injected config threading (see `scala#class-scope-for-helper-bundles`). A short implementation is an anonymous instance built in the companion's `apply`: `def apply(...): Xxx = new Xxx: ...` — no one-use class file, same test seam.
- **Config travels inside the algebra** — `class Scorer(using config: ScorerConfig)` keeps the config out of every caller's signature. Threading the config explicitly through every method that calls a scorer is a sign the algebra wasn't classified yet.

```scala
// Bad — config threaded through every caller
def scoreSegment(seg: Segment, config: ScorerConfig): Score = ...

// Good — config travels inside the algebra; callers see only the domain types
class Scorer(using config: ScorerConfig):
  def score(seg: Segment): Score = ...
```

**Error wrapping at the algebra boundary**

Algebras that call into external services (HTTP clients, SDKs, DB drivers) translate raw client errors into the project's typed error ADT *before* returning. The boundary keeps the rest of the system free of leaky transport-layer error types.

```scala
// In an algebra: external client errors become domain errors
def fetchAccount(id: AccountId): F[Either[AccountError, Account]] =
  client.get(id).attempt.map:
    case Right(account)              => account.asRight
    case Left(_: NotFoundException)  => AccountError.NotFound(id).asLeft
    case Left(_: TimeoutException)   => AccountError.Unavailable.asLeft
    case Left(other)                 => AccountError.Unknown(other).asLeft
```

A generic `wrapServiceError` extension (parameterized over any error supertype) is a good candidate when this pattern recurs across algebras.

**Package layout for algebras and services**
- **Don't bundle everything under one `alg` (or `services`) package.** A flat catch-all package grows into a junk drawer; group by domain area instead.
- **A domain area gets its own subpackage, trait at the root, implementations inside.** For an external-provider integration or a cohesive feature: `<domain>/Foo.scala` (trait + companion) with `<domain>/impl/` or provider-named impls *inside* that subpackage — implementations never sit outside the namespace of the trait they implement.
- **The `impl` (sub)package holds only implementations of the sibling trait** — a standalone class in `impl` with no trait above it is misfiled (it's a service or algebra; give it its trait and place it accordingly).

**Repositories**
- Defined as a `trait` with one SQL implementation per backend.
- No service or algebra dependencies — only the DB session and model types.

**Logic traits**
- Contain only `endpoint.serverLogic` wiring — no inline business logic.
- Always use **named `using` parameters**; never call `summon[X]`.

## Configuration

- **One config class per file**, in the `config` subpackage of the project (e.g. `com.example.server.config.ScorerConfig`).
- **Group related tunables into a nested subconfig.** A cluster of fields that travel together — timing/retry values, rate limits, a feature's thresholds — becomes its own case class nested in the parent config (`RetryConfig(maxAttempts, backoff, timeout)`), not flat siblings in a settings bag. Same instinct as `scala`'s field-grouping rule, applied to config.
- **`derives ConfigReader`** — let pureconfig handle the parsing. No hand-written readers.
- **No in-code defaults** — defaults belong in `application.conf`, where ops can override them per environment without recompiling. Hard-coded fallback values like `config.x.getOrElse(0.5)` defeat the purpose of having a config.
- **Wired once in `Server.scala`** — load all configs at startup, fail-fast if any are malformed, then inject as `using` parameters into the algebras that need them. No re-reading config inside method bodies.
- **Iron refined types in config** — bound the values at config load time (`PosInt`, `NonEmptyString`, `Probability`) so the rest of the system can trust them.
- **Surface tuning constants to `application.conf`.** A numeric threshold or limit baked into an algebra or extension (`minDistanceMeters = 200.0`, `maxResults = 5`) belongs in a config class so ops can tune it per environment without recompiling. Lift it onto the relevant `*Config` and inject the config; don't leave the magic number in the algorithm. Genuine micro-constants (loop bounds, unit conversions, mathematical constants) stay inline. Cross-link `factor-out` — a bare repeated literal is a missing named value.
- **The `config` subpackage holds config classes only.** A domain concept does not belong in `config` just because some of its values are tunable. A `Temperature` value type is a domain type — it lives in its domain package; its tunable bounds live in a `*Config derives ConfigReader`. Putting the type itself under `config` misfiles the domain model and signals the type was reached for as a settings bag rather than modelled.

```scala
// config/ScorerConfig.scala
final case class ScorerConfig(
  weight: Probability,
  minSamples: PosInt
) derives ConfigReader

// Server.scala — load, then inject
for
  scorerConfig <- ConfigSource.default.at("scorer").loadF[F, ScorerConfig]
  given ScorerConfig = scorerConfig
  scorer = new Scorer  // picks up the given
  ...
yield ()
```

## Output expectations
- Every new external capability is isolated in a `Service`; all orchestration across capabilities lives in an `Algebra`.
- No `Service` imports another `Service` directly.
- Logic traits contain no business logic — only delegation via `using` parameters.
- Every non-trivial algebra is a `trait` (the test seam) with a `class XxxImpl(using config)` implementation; configs travel inside the algebra, not threaded through callers.
- All config classes live in the `config` subpackage with `derives ConfigReader`, sourced from `application.conf` — no in-code defaults.
- Algebras translate external/transport errors into the project's typed error ADT at their boundary.