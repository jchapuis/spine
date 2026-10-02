import skunk.*
import cats.effect.IO
import natchez.Trace.Implicits.noop

val pool = Session.pooled[IO](host = "localhost", port = 5432, user = "app", database = "db", max = 10)
