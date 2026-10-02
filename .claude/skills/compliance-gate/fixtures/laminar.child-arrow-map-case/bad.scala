import com.raquo.laminar.api.L.*
import com.raquo.airstream.core.Signal

sealed trait Catalog
case object BuiltIn extends Catalog
case object Garmin  extends Catalog

def render(selection: Signal[Catalog]): HtmlElement =
  div(child <-- selection.map { case BuiltIn => div() case Garmin => div() })
