private[mypkg] def helper(x: Int): Int = x * 2

private[internal] object SharedBag:
  def label(x: Int) = s"item-$x"

protected[othermod] class InternalHelper
