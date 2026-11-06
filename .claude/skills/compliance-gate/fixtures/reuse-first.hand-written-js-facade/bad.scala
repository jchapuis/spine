import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobal

@js.native
@JSGlobal("TextEncoder")
class TextEncoder() extends js.Object:
  def encode(input: String): js.typedarray.Uint8Array = js.native
