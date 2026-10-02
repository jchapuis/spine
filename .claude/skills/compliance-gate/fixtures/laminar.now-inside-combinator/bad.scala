import com.raquo.airstream.core.Signal

def combined(left: Signal[Int], right: Signal[Int]): Signal[Int] =
  left.map(value => value + right.now())
