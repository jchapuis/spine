type Age = Age.T
object Age extends RefinedType[Int, GreaterEqual[0]]

type Ratio = Ratio.T
object Ratio extends RefinedType[Double, GreaterEqual[0.0] & LessEqual[1.0]]
