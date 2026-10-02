import scala.concurrent.duration.*
import java.util.concurrent.TimeUnit.*

def asHours(duration: FiniteDuration): Double = duration.toUnit(HOURS)
