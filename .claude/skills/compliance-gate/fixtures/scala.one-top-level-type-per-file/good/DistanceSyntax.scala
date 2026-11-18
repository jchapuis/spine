package compliancegate.fixtures

import io.circe.Encoder

extension (meters: Double)
  def kilometers: Double = meters / 1000

given Encoder[java.time.Duration] = Encoder.encodeLong.contramap(_.toSeconds)

def roundTo(value: Double, places: Int): Double =
  BigDecimal(value).setScale(places, BigDecimal.RoundingMode.HALF_UP).toDouble
