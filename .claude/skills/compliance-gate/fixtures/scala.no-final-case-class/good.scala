case class Point(x: Int, y: Int)
private case class Inner(x: Int)
sealed case class Tagged(x: Int)
@deprecated case class Outdated(x: Int)

object Outer:
  case class Nested(x: Int)

val FinalAnswer = 42
val notice = "final case class is dead in Scala 3"
