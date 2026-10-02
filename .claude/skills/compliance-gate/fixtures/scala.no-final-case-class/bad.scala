final case class Point(x: Int, y: Int)
private final case class Inner(x: Int)
sealed final case class Tagged(x: Int)
@deprecated final case class Outdated(x: Int)

object Outer:
  final case class Nested(x: Int)
