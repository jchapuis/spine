class Age(val years: Int):
  require(years >= 0)

class Ratio(val value: Double):
  require(value >= 0.0 && value <= 1.0)
