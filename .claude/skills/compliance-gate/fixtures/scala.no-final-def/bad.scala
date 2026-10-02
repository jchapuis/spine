trait Greeter:
  final def hello(name: String): String = s"Hello, $name"

class Specific extends Greeter:
  private final def secret(): Int = 42

object Outer:
  @inline final def fast(x: Int): Int = x + 1
