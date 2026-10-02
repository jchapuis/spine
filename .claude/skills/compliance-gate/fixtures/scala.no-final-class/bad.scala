final class TokenValidator(secret: String):
  def validate(token: String): Boolean = token.nonEmpty

object Wrapper:
  private final class Inner(value: Int):
    def doubled: Int = value * 2
