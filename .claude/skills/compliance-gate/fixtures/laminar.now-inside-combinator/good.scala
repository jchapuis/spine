import com.raquo.airstream.core.Signal

def combined(left: Signal[Int], right: Signal[Int]): Signal[Int] =
  left.withCurrentValueOf(right).map((leftValue, rightValue) => leftValue + rightValue)
