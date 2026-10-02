def divide(left: Int, right: Int): Int =
  if right == 0 then throw new ArithmeticException("divide by zero")
  else left / right
