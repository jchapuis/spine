def divide(left: Int, right: Int): Either[String, Int] =
  if right == 0 then Left("divide by zero")
  else Right(left / right)
