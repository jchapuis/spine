import cats.effect.{IO, Resource}

def connection: Resource[IO, String] =
  Resource.make(IO.pure("open"))(_ => IO.println("closing"))
