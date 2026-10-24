package com.acme.pipeline

import fs2.Stream
import cats.effect.IO

val stream: Stream[IO, Int] = Stream.emits(1 to 100).parEvalMapUnbounded(value => IO.pure(value + 1))
