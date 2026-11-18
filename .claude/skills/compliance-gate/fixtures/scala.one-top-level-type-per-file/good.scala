package compliancegate.fixtures

sealed trait RouteSwitch:
  def reason: String

object RouteSwitch:
  case class Manual(reason: String) extends RouteSwitch
  case class Automatic(reason: String) extends RouteSwitch

  def manual(reason: String): RouteSwitch = Manual(reason)

extension (switch: RouteSwitch)
  def isManual: Boolean = switch match
    case RouteSwitch.Manual(_)    => true
    case RouteSwitch.Automatic(_) => false

given Ordering[RouteSwitch] = Ordering.by(_.reason)

val defaultReason = "user"

def describe(switch: RouteSwitch): String = switch.reason
