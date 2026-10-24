package com.acme.pipeline

import cats.Monoid
import cats.syntax.semigroup.*

val total = List(1, 2, 3).foldLeft(Monoid[Int].empty)(_ |+| _)
