def firstPositive(numbers: List[Int]): Option[Int] =
  numbers.foreach: number =>
    if number > 0 then return Some(number)
  None
