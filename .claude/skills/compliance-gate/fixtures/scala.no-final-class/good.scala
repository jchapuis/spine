class TokenValidator(secret: String):
  def validate(token: String): Boolean = token.nonEmpty

case class Wrapped(value: Int):
  def doubled: Int = value * 2

object Finalizer:
  val finalCount: Int = 3
