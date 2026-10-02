trait Greeter:
  def hello(name: String): String = s"Hello, $name"

class Specific extends Greeter:
  private def secret(): Int = 42

object Outer:
  @inline def fast(x: Int): Int = x + 1

  def finalize(input: String): String = input.trim
  val finalCount = 10
