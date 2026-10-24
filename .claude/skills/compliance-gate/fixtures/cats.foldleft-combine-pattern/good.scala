package com.acme.pipeline

import cats.syntax.foldable.*

val total = List(1, 2, 3).combineAll
