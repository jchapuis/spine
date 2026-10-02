def sum(numbers: List[Int]): Int =
  var total = 0
  var remaining = numbers
  while remaining.nonEmpty do
    total = total + remaining.head
    remaining = remaining.tail
  total
