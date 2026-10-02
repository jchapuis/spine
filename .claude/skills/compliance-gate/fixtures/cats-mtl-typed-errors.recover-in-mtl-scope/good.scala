import cats.effect.IO
import cats.mtl.Raise

def importRaster(input: String)(using Raise[IO, String]): IO[Unit] =
  IO.unit.flatMap(_ => IO.pure(()))
