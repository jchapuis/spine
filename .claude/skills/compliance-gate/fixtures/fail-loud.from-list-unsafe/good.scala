import cats.data.NonEmptyList

def parse(values: List[Int]): Option[NonEmptyList[Int]] =
  NonEmptyList.fromList(values)
