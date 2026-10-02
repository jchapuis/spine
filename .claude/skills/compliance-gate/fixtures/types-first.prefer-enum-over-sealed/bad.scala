sealed trait PaymentMethod
case object Card extends PaymentMethod
case object Cash extends PaymentMethod

sealed abstract class Shape
