import cats.effect.IO
import cats.effect.unsafe.implicits.global

val result = IO.pure(42).unsafeRunSync()
