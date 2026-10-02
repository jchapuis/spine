class Connection:
  override def finalize(): Unit =
    println("closing")
