import fs2.Stream
import cats.effect.IO

val stream: Stream[IO, Int] = Stream.emits(1 to 100).parEvalMap(4)(value => IO.pure(value + 1))
