package compliancegate.fixtures

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MaxLength
import io.github.iltotore.iron.constraint.string.Match

type MetersBudget = 12

type MetersFormat = Match["^[0-9]+$"] DescribedAs "Must be digits"

type Meters = Meters.T

object Meters extends RefinedSubtype[String, MetersFormat & MaxLength[MetersBudget]]:
  val zero: Meters = Meters("0")
