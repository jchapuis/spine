import cats.effect.IO
import cats.mtl.Raise

def importRaster(input: String)(using Raise[IO, String]): IO[Unit] =
  IO.unit.handleErrorWith(t => Raise[IO, String].raise(t.getMessage))
