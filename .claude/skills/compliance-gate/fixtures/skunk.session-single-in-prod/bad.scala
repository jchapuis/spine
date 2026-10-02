import skunk.*
import cats.effect.IO
import natchez.Trace.Implicits.noop

val session = Session.single[IO](host = "localhost", port = 5432, user = "app", database = "db")
