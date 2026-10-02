import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*

sealed trait Page
object Home extends Page
object About extends Page

def renderRouter(router: Router[Page]): HtmlElement =
  div(child <-- router.currentPageSignal.map(page => span(page.toString)))
