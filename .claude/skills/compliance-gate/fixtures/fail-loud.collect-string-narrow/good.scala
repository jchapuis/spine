case class Label(name: String)

def labelNames(labels: List[Label]): List[String] =
  labels.map(_.name)
