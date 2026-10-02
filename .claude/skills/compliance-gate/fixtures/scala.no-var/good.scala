case class Counter(count: Int = 0):
  def incremented: Counter = copy(count = count + 1)
