# http4s — Functional HTTP Server Reference (Scala)

Sources:
- https://http4s.org/v0.23/docs/dsl.html
- https://http4s.org/v0.23/docs/server-middleware.html
- https://http4s.org/v0.23/docs/deployment.html
- https://github.com/http4s/http4s/blob/series/0.23/docs/docs/server-middleware.md

## Table of Contents
- [HttpRoutes DSL](#httproutes-dsl)
- [Request decoding](#request-decoding)
- [Response helpers](#response-helpers)
- [Combining routes](#combining-routes)
- [Tapir integration](#tapir-integration)
- [Middleware](#middleware)
- [Server builder (Netty)](#server-builder-netty)
- [Server builder (Ember)](#server-builder-ember)
- [Static assets](#static-assets)
- [WebSockets](#websockets)

---

## HttpRoutes DSL

```scala
import cats.effect.IO
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*

val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
  // GET /users
  case GET -> Root / "users" => Ok("all users")

  // GET /users/42
  case GET -> Root / "users" / IntVar(id) => Ok(s"user $id")

  // GET /users/abc-123 (UUID)
  case GET -> Root / "users" / UUIDVar(id) => Ok(s"user $id")

  // POST /users
  case req @ POST -> Root / "users" =>
    req.as[String].flatMap(body => Created(body))

  // DELETE /users/42
  case DELETE -> Root / "users" / IntVar(id) => NoContent()

  // Query parameters:
  case GET -> Root / "search" :? QueryParamDecoderMatcher[String]("q")(q) =>
    Ok(s"searching for $q")
}
```

---

## Request decoding

```scala
import org.http4s.circe.CirceEntityCodec.*  // or upickle equivalent
import io.circe.generic.semiauto.*

case class CreateUser(name: String, age: Int)
given Decoder[CreateUser] = deriveDecoder

val createRoute: HttpRoutes[IO] = HttpRoutes.of[IO] {
  case req @ POST -> Root / "users" =>
    req.as[CreateUser].flatMap { user =>
      userService.create(user).flatMap(created => Created(created))
    }
}

// Manual body as String/Array:
req.as[String]
req.body.compile.toVector.map(_.toArray)
```

---

## Response helpers

```scala
// Success:
Ok(body)          // 200
Created(body)     // 201
Accepted()        // 202
NoContent()       // 204

// Client errors:
BadRequest(msg)   // 400
Unauthorized()    // 401
Forbidden()       // 403
NotFound()        // 404
Conflict(msg)     // 409

// Server errors:
InternalServerError(msg)  // 500

// Custom response:
IO.pure(
  Response[IO](status = Status.UnprocessableEntity)
    .withEntity("Validation failed")
)
```

---

## Combining routes

```scala
import org.http4s.server.Router
import org.http4s.implicits.*

val apiRoutes: HttpRoutes[IO] = ???
val assetRoutes: HttpRoutes[IO] = ???
val healthRoutes: HttpRoutes[IO] = ???

// Prefix-based routing:
val combined: HttpRoutes[IO] = Router(
  "/api/v1" -> apiRoutes,
  "/health" -> healthRoutes,
  "/"       -> assetRoutes
)

// HttpApp (required by server builders):
val app: HttpApp[IO] = combined.orNotFound
```

---

## Tapir integration

```scala
import sttp.tapir.server.http4s.Http4sServerInterpreter
import cats.effect.IO

// Single endpoint:
val routes: HttpRoutes[IO] =
  Http4sServerInterpreter[IO]().toRoutes(
    myEndpoint.serverLogic { input =>
      service.handle(input).map(Right(_))
    }
  )

// Multiple endpoints as a list:
val allRoutes: HttpRoutes[IO] =
  Http4sServerInterpreter[IO]().toRoutes(
    List(
      listEndpoint.serverLogic(...),
      getEndpoint.serverLogic(...),
      createEndpoint.serverLogic(...)
    )
  )

// Mix Tapir routes with raw routes (e.g., health check):
val combined = Router(
  "/api" -> tapirRoutes,
  "/"    -> rawRoutes
)
```

---

## Middleware

```scala
import org.http4s.server.middleware.*

// Request/response logging:
val loggedApp: HttpApp[IO] =
  Logger.httpApp[IO](
    logHeaders = true,
    logBody    = false
  )(app)

// CORS:
import org.http4s.server.middleware.CORS
val corsApp = CORS.policy
  .withAllowOriginAll
  .httpApp(app)

// Request ID (for log correlation):
val requestIdRoutes: HttpRoutes[IO] = RequestId.httpRoutes(routes)

// Auto trailing slash removal:
val normalized: HttpApp[IO] = AutoSlash.httpApp(app)

// GZip compression:
import org.http4s.server.middleware.GZip
val gzipped = GZip(app)

// Middleware composition (outer wraps inner):
val finalApp =
  Logger.httpApp[IO](true, false)(
    GZip(
      combined.orNotFound
    )
  )
```

---

## Server builder (Netty)

```scala
import org.http4s.netty.server.NettyServerBuilder
import cats.effect.{IO, Resource}
import com.comcast.ip4s.*

val serverResource: Resource[IO, Unit] =
  NettyServerBuilder[IO]
    .bindHttp(8080, "0.0.0.0")
    .withHttpApp(app)
    .resource
    .void

// With custom thread pool and TLS:
NettyServerBuilder[IO]
  .bindHttp(port = 8443, host = "0.0.0.0")
  .withNioTransport
  .withSslContext(sslCtx)
  .withHttpApp(app)
  .resource
```

---

## Server builder (Ember)

Default for new projects (pure Scala, no Netty dependency):

```scala
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*

val serverResource: Resource[IO, Unit] =
  EmberServerBuilder.default[IO]
    .withHost(host"0.0.0.0")
    .withPort(port"8080")
    .withHttpApp(app)
    .build
    .void
```

---

## Static assets

```scala
import org.http4s.StaticFile

val assetRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
  case req @ GET -> Root / "assets" / path =>
    StaticFile
      .fromResource[IO](s"/static/$path", Some(req))
      .getOrElseF(NotFound())
}
```

---

## WebSockets

```scala
import org.http4s.websocket.WebSocketFrame
import org.http4s.server.websocket.WebSocketBuilder2
import fs2.*

def wsRoutes(wsb: WebSocketBuilder2[IO]): HttpRoutes[IO] =
  HttpRoutes.of[IO] {
    case GET -> Root / "ws" =>
      val send: Stream[IO, WebSocketFrame] =
        Stream.awakeEvery[IO](1.second)
          .map(d => WebSocketFrame.Text(s"ping $d"))

      val receive: Pipe[IO, WebSocketFrame, Unit] =
        _.evalMap(frame => IO.println(s"received: $frame"))

      wsb.build(send, receive)
  }

// Wire in server builder:
EmberServerBuilder.default[IO]
  .withHttpWebSocketApp(wsb => wsRoutes(wsb).orNotFound)
  .build
```
