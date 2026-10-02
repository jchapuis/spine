import cats.effect.IO

val result: String = IO.pure("done").unsafeRunSync()
