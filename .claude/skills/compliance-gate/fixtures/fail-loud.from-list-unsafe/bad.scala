import cats.data.NonEmptyList

def parse(values: List[Int]): NonEmptyList[Int] =
  NonEmptyList.fromListUnsafe(values)
