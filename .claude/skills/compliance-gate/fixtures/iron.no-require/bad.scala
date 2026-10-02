object Account:
  def open(balance: Double): Account =
    require(balance >= 0, "balance must be non-negative")
    Account(balance)

case class Account(balance: Double)
