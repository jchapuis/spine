import com.raquo.laminar.api.L.*
import scala.scalajs.js

def delayed(action: () => Unit): Unit =
  js.timers.setTimeout(500)(action())
