import cats.effect.IO
import cats.effect.IOApp

object Application extends IOApp.Simple:
  def run: IO[Unit] = IO.println("done")
