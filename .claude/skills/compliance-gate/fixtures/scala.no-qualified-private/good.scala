private def helper(x: Int): Int = x * 2

object SharedBag:
  def label(x: Int) = s"item-$x"

class InternalHelper:
  private[this] val internal = 42
