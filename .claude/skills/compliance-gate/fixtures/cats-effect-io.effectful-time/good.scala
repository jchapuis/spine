import cats.effect.IO
import java.time.Instant

object Stamps:
  def stamp: IO[Instant] = IO.realTimeInstant
  def elapsed(start: Instant): IO[Long] =
    IO.realTimeInstant.map(now => now.toEpochMilli - start.toEpochMilli)
