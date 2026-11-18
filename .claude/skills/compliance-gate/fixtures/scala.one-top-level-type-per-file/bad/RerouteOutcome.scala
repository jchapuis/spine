package compliancegate.fixtures

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive

enum ByTimeReason:
  case Faster, Shorter

case class DirectRoute(distanceMeters: Double)

enum RerouteOutcome:
  case Rerouted(route: DirectRoute)
  case Kept(reason: ByTimeReason)

object RerouteOutcome:
  val default: RerouteOutcome = RerouteOutcome.Kept(ByTimeReason.Faster)

trait RouteLineage

object Helpers:
  def describe(outcome: RerouteOutcome): String = outcome.toString

type Meters = Meters.T
object Meters extends RefinedType[Double, Positive]

type Seconds = Double

type UnusedFormat = Match["^[0-9]+$"]
