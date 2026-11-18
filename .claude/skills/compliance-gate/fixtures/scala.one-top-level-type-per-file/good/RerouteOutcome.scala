package compliancegate.fixtures

enum RerouteOutcome:
  case Rerouted(distanceMeters: Double)
  case Kept

object RerouteOutcome:
  val default: RerouteOutcome = Kept
