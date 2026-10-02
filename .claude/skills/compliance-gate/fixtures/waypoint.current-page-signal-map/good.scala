import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*

sealed trait Page
object Home extends Page
object About extends Page

def renderRouter(router: Router[Page]): HtmlElement =
  div(
    child <-- SplitRender[Page, HtmlElement](router.currentPageSignal)
      .collectStatic(Home)(span("home"))
      .collectStatic(About)(span("about"))
      .signal
  )
