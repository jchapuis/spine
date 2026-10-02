import com.raquo.laminar.api.L.*
import scala.scalajs.js

def debug(signal: Signal[String]): Unit =
  signal.foreach(value => js.Dynamic.global.console.log(js.JSON.stringify(value)))(unsafeWindowOwner)
