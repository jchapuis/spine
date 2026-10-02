# Tapir — Type-Safe HTTP Endpoints Reference (Scala)

Sources:
- https://github.com/softwaremill/tapir/blob/master/generated-doc/out/index.md
- https://github.com/softwaremill/tapir/blob/master/generated-doc/out/server/http4s.md
- https://github.com/softwaremill/tapir/blob/master/generated-doc/out/tutorials/07_cats_effect.md
- https://github.com/softwaremill/tapir/blob/master/generated-doc/out/tutorials/04_errors.md
- https://github.com/softwaremill/tapir/blob/master/generated-doc/out/endpoint/schemas.md
- https://github.com/softwaremill/tapir/blob/master/generated-doc/out/endpoint/integrations.md

## Table of Contents
- [Endpoint anatomy](#endpoint-anatomy)
- [Inputs and outputs](#inputs-and-outputs)
- [Error handling](#error-handling)
- [Schema derivation](#schema-derivation)
- [JSON codecs](#json-codecs)
- [Iron integration](#iron-integration)
- [Server: http4s](#server-http4s)
- [Client: STTP](#client-sttp)
- [Security / authentication](#security--authentication)
- [Status codes](#status-codes)
- [Shared module pattern](#shared-module-pattern)

---

## Endpoint anatomy

```scala
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*

// PublicEndpoint[Input, ErrorOutput, SuccessOutput, Requirements]
val listUsers: PublicEndpoint[Unit, String, List[User], Any] =
  endpoint.get
    .in("users")
    .out(jsonBody[List[User]])
    .errorOut(stringBody)
```

---

## Inputs and outputs

```scala
// Path segments
.in("users" / path[Int]("id"))

// Query parameters
.in(query[String]("search"))
.in(query[Option[Int]]("page"))

// Request body
.in(jsonBody[CreateUserRequest])

// Headers
.in(header[String]("X-Correlation-Id"))

// Multiple inputs — auto-tupled:
.in("users" / path[Int]("id"))
.in(query[String]("fields"))
// input type is (Int, String)

// Map to case class (bidirectional):
case class GetUser(id: Int, fields: String)
.in("users" / path[Int]("id"))
.in(query[String]("fields"))
.mapInTo[GetUser]  // or .mapTo for combined in/out

// Output with status:
.out(jsonBody[User])
.out(statusCode(StatusCode.Created))
```

---

## Error handling

```scala
import sttp.model.StatusCode

// Typed error output:
sealed trait ApiError
case class NotFound(msg: String)       extends ApiError
case class Unauthorized(msg: String)   extends ApiError

given Schema[ApiError] = Schema.derived

val ep = endpoint.get
  .in("users" / path[Int]("id"))
  .out(jsonBody[User])
  .errorOut(
    oneOf[ApiError](
      oneOfVariant(StatusCode.NotFound, jsonBody[NotFound]),
      oneOfVariant(StatusCode.Unauthorized, jsonBody[Unauthorized])
    )
  )

// Server logic returns F[Either[E, O]]:
ep.serverLogic { id =>
  userService.find(id).map {
    case Some(u) => Right(u)
    case None    => Left(NotFound(s"User $id not found"))
  }
}
```

---

## Schema derivation

```scala
import sttp.tapir.generic.auto.*   // auto-derivation via magnolia
// OR explicit (Scala 3 mirror):
given Schema[User] = Schema.derived

// For ADTs with a discriminator field:
given Schema[ApiError] = Schema.derived

// Union types (Scala 3):
val s: Schema[String | Int] = Schema.derivedUnion

// Avoid Schema.derived for generic types with type parameters;
// prefer Schema.derived on concrete instantiations.
```

---

## JSON codecs

```scala
// Circe:
import sttp.tapir.json.circe.*
import io.circe.generic.semiauto.*
given Encoder[User] = deriveEncoder
given Decoder[User] = deriveDecoder

// Upickle:
import sttp.tapir.json.upickle.*
import upickle.default.*
given ReadWriter[User] = macroRW
```

---

## Iron integration

```scala
import sttp.tapir.codec.iron.*   // Schema and Codec for Iron :| refined types
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

// Schema is automatically derived for refined types:
val ep = endpoint.get
  .in("users" / path[UserId]("id"))
  .out(jsonBody[User])
// No explicit Schema[UserId] needed — provided by tapir-codec-iron
```

---

## Server: http4s

```scala
import sttp.tapir.server.http4s.Http4sServerInterpreter
import cats.effect.IO
import org.http4s.HttpRoutes

// Single endpoint:
val routes: HttpRoutes[IO] =
  Http4sServerInterpreter[IO]().toRoutes(
    listUsersEndpoint.serverLogic(_ => userService.list.map(Right(_)))
  )

// Multiple endpoints:
val allRoutes: HttpRoutes[IO] =
  Http4sServerInterpreter[IO]().toRoutes(
    List(listUsersEndpoint.serverLogic(...), getUserEndpoint.serverLogic(...))
  )
```

---

## Client: STTP

```scala
import sttp.tapir.client.sttp4.SttpClientInterpreter
import sttp.client4.*
import sttp.client4.httpclient.cats.HttpClientCatsBackend

// Build a request from an endpoint definition:
val request = SttpClientInterpreter()
  .toRequest(getUserEndpoint, Some(uri"http://localhost:8080"))
  .apply(42)  // the endpoint input

// Send it:
HttpClientCatsBackend.resource[IO]().use { backend =>
  backend.send(request).map(_.body)
}
```

---

## Security / authentication

```scala
import sttp.tapir.*

case class AuthInfo(userId: UserId)

val securedEndpoint =
  endpoint
    .securityIn(auth.bearer[String]())
    .errorOut(jsonBody[Unauthorized])

// Attach security logic (runs first, returns Either[E, AuthInfo]):
val serverEndpoint = securedEndpoint
  .serverSecurityLogic { token =>
    authService.validate(token).map {
      case Some(info) => Right(info)
      case None       => Left(Unauthorized("invalid token"))
    }
  }
  // Then main logic receives AuthInfo:
  .serverLogic { authInfo => input =>
    userService.find(authInfo.userId).map(Right(_))
  }
```

---

## Status codes

```scala
// Fixed status code in output:
.out(statusCode(StatusCode.Created))
.out(jsonBody[User])

// Dynamic status code alongside body:
.out(statusCode.and(jsonBody[User]))
// server logic returns (StatusCode, User)

// Default: 200 for success, 400 for errorOut — override only when needed.
```

---

## Shared module pattern

```
shared/
  src/
    Endpoints.scala   // endpoint definitions — cross-compiled JVM + JS
    Domain.scala      // request/response types with Schema + JSON codecs

server/
  src/
    Routes.scala      // endpoint.serverLogic(...) → HttpRoutes[F]

client/
  src/
    ApiClient.scala   // SttpClientInterpreter → typed request builders
```

Keep endpoint definitions, domain types, and schema/codec derivations in `shared/`.
Never import server or client dependencies from `shared/`.
