---
name: logging
description: Logging conventions for Scala/Cats Effect projects with log4cats. Use when adding log statements, placing a logger in a class, choosing log level, logging exceptions with a Throwable cause, or deciding where in the codebase logging belongs.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['org\.typelevel\.log4cats', 'Slf4jLogger']
---

# Logging — log4cats / Slf4j (Scala / Cats Effect)

## Quick start
- Library: **log4cats** with the Slf4j backend — `Logger[IO]` from `Slf4jLogger.getLogger[IO]`.
- Always import `org.typelevel.log4cats.syntax.*` and use the interpolation macros (`info"..."`, `warn"..."`, `error"..."`).
- **Classes take the logger as a `(using Logger[IO])` parameter**; traits and objects, which have no constructor, declare a `private given` instead.
- Log at **operation boundaries** after the result is in scope, not before the call.
- **Log success on identity/security boundaries**, not just failure — successful authentication, authorization decision, token validation. Without it, beta-tester usage and incident investigation are unrecoverable.
- **Never log secrets** — token values, passwords, API keys. Log a stable identifier (user id, request id, hash prefix) instead.
- Do **not** log inside pure helpers, repositories, or Scala.js modules.

## Workflow
1. Add a `private given logger: Logger[IO] = Slf4jLogger.getLogger[IO]` at the top of the algebra class or logic trait.
2. For polymorphic `F[_]` classes, use `LoggerFactory[F].getLogger` instead.
3. After each significant operation, emit one log line with the outcome and relevant context in scope.
4. Use the interpolation macros for normal messages; fall back to `logger.error(cause)(message)` only when attaching a `Throwable`.

## Key rules

**Imports**
```scala
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.syntax.*
```

**Logger placement** — a class receives the logger, a trait or object creates one.

A class takes it as a trailing `(using Logger[IO])` clause, anonymous unless the body needs it by
name (`logger.error(cause)(message)` does; the `info"…"` macros do not). This is what lets a test
substitute `NoOpLogger[IO]` instead of writing to the real appender, and it is the only form that
composes when a class already has other `using` dependencies.

```scala
class UserManagement(using service: UserManagementService)(using Logger[IO]):
  def list(slug: String): IO[List[User]] =
    service.list(slug).flatTap(users => info"Listed ${users.size} users for location $slug")
```

A trait or object cannot take a constructor parameter, so it declares its own — this is the case
logic traits fall into:

```scala
trait AuthLogic extends AuthEndpoints:
  private given logger: Logger[IO] = Slf4jLogger.getLogger[IO]
```

**Interpolation macros** — prefer over `logger.info(s"...")`:
```scala
_ <- info"Listed ${users.size} users for location $slug"
_ <- warn"Unexpected state: no assignment found for $slug"
```

**Exception logging** — use `logger.error(cause)(message)` when attaching a `Throwable` (the macro does not accept a separate cause):
```scala
case Error.ApiError(operation, cause) =>
  logger.error(cause)(s"Service error during $operation")
```

**Identity / security boundary logging** — log both success and failure of authentication, token validation, and authorization decisions. Without a success line, you cannot reconstruct who used the system from logs alone. Include a stable identifier; never the credential value.
```scala
// Token validation algebra
def validateToken(token: BearerToken): IO[Either[AuthError, Identity]] =
  validate(token).flatTap:
    case Right(identity) => info"auth_ok user_id=${identity.userId} email=${identity.email}"
    case Left(error)     => warn"auth_failed reason=${error.code}"
```
A common trap: only the failure case is logged because success is the "happy path" and feels noisy. When ops needs to answer "did user X log in this week?", failure-only logs are useless.

**Do not log:**
- Inside pure helper functions
- At the repository layer (Skunk tracing covers SQL observability)
- In shared or Scala.js (client) modules
- Before an operation — log the outcome, not the intent
- **Secrets, tokens, or credential values** — even partially. Hash, prefix, or substitute with a stable id (user id, request id).

## Output expectations
- Every algebra class and logic trait that performs I/O has exactly one `Logger[IO]` in scope, placed per the rule above.
- All normal log calls use the interpolation macros; `logger.error(cause)(...)` is used only when a `Throwable` must be attached.
- Classes receive `Logger[IO]` through a `using` clause; only traits and objects construct their own.