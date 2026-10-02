import com.raquo.laminar.api.L.*
import com.raquo.airstream.core.Signal

sealed trait Catalog
case object BuiltIn extends Catalog
case object Garmin  extends Catalog

def render(selection: Signal[Catalog]): HtmlElement =
  div(
    child <-- selection
      .splitMatchOne
      .handleType[BuiltIn.type](_.mapTo(div()))
      .handleType[Garmin.type](_.mapTo(div()))
      .toSignal
  )
