import scala.concurrent.duration.*

extension (duration: FiniteDuration)
  def inHours: Double = duration.toMillis.toDouble / 3600000.0

def asHours(duration: FiniteDuration): Double = duration.inHours
