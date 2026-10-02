# Waypoint — Client-Side Routing Reference (Scala.js)

Sources:
- https://github.com/raquo/waypoint/blob/master/README.md

## Table of Contents
- [Page ADT](#page-adt)
- [URL DSL](#url-dsl)
- [Route types](#route-types)
- [Router setup](#router-setup)
- [SplitRender](#splitrender)
- [Navigation](#navigation)
- [Integration with Laminar](#integration-with-laminar)
- [Nested routes](#nested-routes)

---

## Page ADT

```scala
// Define all pages as a sealed hierarchy:
sealed trait Page

sealed trait AppPage extends Page
case class UserPage(userId: Int)     extends AppPage
case class NotePage(noteId: String)  extends AppPage
case object DashboardPage            extends AppPage

case object LoginPage extends Page
case object NotFoundPage extends Page
```

---

## URL DSL

```scala
import com.raquo.waypoint.*

// Static path:
root / "login" / endOfSegments
// matches exactly /login

// Path with typed segment:
root / "users" / segment[Int] / endOfSegments
// matches /users/42 — decodes to Int

// Multiple segments:
root / "workspace" / segment[Int] / "notes" / segment[String] / endOfSegments
// matches /workspace/7/notes/abc — decodes to (Int, String)

// Query parameter:
(root / "search" / endOfSegments) ? param[String]("q")
// matches /search?q=hello

// Optional query parameter:
(root / "users" / segment[Int] / endOfSegments) ? (param[String]("tab") & param[Int]("page").?)
// page is Optional[Int]

// Fragment:
(root / "legal" / segment[String] / endOfSegments) withFragment fragment[String]
```

---

## Route types

```scala
import com.raquo.waypoint.*
import urldsl.vocabulary.UrlMatching

// Static (no parameters):
val loginRoute = Route.static(LoginPage, root / "login" / endOfSegments)

// Parameterized path:
val userRoute = Route[UserPage, Int](
  encode = page => page.userId,
  decode = id   => UserPage(userId = id),
  pattern = root / "users" / segment[Int] / endOfSegments
)

// Path + query parameters:
val searchRoute = Route.withQuery[SearchPage, String, Option[Int]](
  encode = page => UrlMatching(path = page.query, params = page.page),
  decode = args => SearchPage(query = args.path, page = args.params),
  pattern = (root / "search" / segment[String] / endOfSegments) ? param[Int]("page").?
)

// Fragment:
val legalRoute = Route.withFragment[LegalPage, String, String](
  encode = page => FragmentPatternArgs(path = page.slug, query = (), fragment = page.section),
  decode = args => LegalPage(slug = args.path, section = args.fragment),
  pattern = (root / "legal" / segment[String] / endOfSegments) withFragment fragment[String]
)
```

---

## Router setup

```scala
import com.raquo.waypoint.*
import com.raquo.laminar.api.L.*

val router: Router[Page] = new Router[Page](
  routes = List(loginRoute, userRoute, dashboardRoute),

  // Title shown in browser tab:
  getPageTitle = {
    case LoginPage       => "Login"
    case DashboardPage   => "Dashboard"
    case UserPage(id)    => s"User $id"
    case _               => "App"
  },

  // Serialize page to browser history state (for back/forward):
  serializePage = page => page.toString,   // or use upickle/circe
  deserializePage = _ => LoginPage,         // or proper deserialization

)(
  popStateEvents = windowEvents(_.onPopState),
  owner          = unsafeWindowOwner        // top-level Laminar owner
)
```

---

## SplitRender

Always use `SplitRender` to render pages — never `.map` on `currentPageSignal` directly.

```scala
import com.raquo.waypoint.SplitRender
import com.raquo.laminar.api.L.*

// Simple splitter:
val splitter = SplitRender[Page, HtmlElement](router.currentPageSignal)
  .collectSignal[UserPage]  { userSignal  => renderUser(userSignal) }
  .collectSignal[NotePage]  { noteSignal  => renderNote(noteSignal) }
  .collectStatic(DashboardPage) { renderDashboard() }
  .collectStatic(LoginPage)     { renderLogin() }

// Wire into DOM:
div(
  child <-- splitter.signal
)

// Parameterised render — receives a Signal[SubPage], not just the page:
def renderUser(userSignal: Signal[UserPage]): HtmlElement =
  div(
    h1("User"),
    child.text <-- userSignal.map(p => s"ID: ${p.userId}")
  )
```

---

## Navigation

```scala
// Push a new page (adds to browser history):
router.pushState(UserPage(42))

// Replace current entry (no new history entry):
router.replaceState(LoginPage)

// Observer for use in Laminar event streams:
val navigateTo: Observer[Page] = Observer(router.pushState)

// Use in a button click handler:
button(
  "Go to user",
  onClick.mapTo(UserPage(42)) --> navigateTo
)

// Programmatic navigation from outside Laminar:
router.pushState(DashboardPage)

// Absolute URL for a page (for links, sharing):
router.absoluteUrlForPage(UserPage(42))  // "https://app.example.com/users/42"
```

---

## Integration with Laminar

```scala
import com.raquo.laminar.api.L.*
import com.raquo.waypoint.*

// Pass router as given or explicit parameter to sub-components:
def navBar(using router: Router[Page]): HtmlElement =
  nav(
    a(
      "Dashboard",
      href := router.absoluteUrlForPage(DashboardPage),
      onClick.preventDefault.mapTo(DashboardPage) --> Observer(router.pushState)
    )
  )

// Derive sub-state from current page signal:
val currentUserId: Signal[Option[Int]] =
  router.currentPageSignal.map {
    case UserPage(id) => Some(id)
    case _            => None
  }
```

---

## Nested routes

```scala
// Use nested SplitRender for common layout wrapping:
val appSplitter = SplitRender[Page, HtmlElement](router.currentPageSignal)
  .collectSignal[AppPage] { appPageSignal =>
    div(
      cls := "app-layout",
      navBar,
      child <-- SplitRender[AppPage, HtmlElement](appPageSignal)
        .collectSignal[UserPage] { renderUser }
        .collectSignal[NotePage] { renderNote }
        .collectStatic(DashboardPage) { renderDashboard() }
        .signal
    )
  }
  .collectStatic(LoginPage) { renderLogin() }
  .collectStatic(NotFoundPage) { div("Not found") }
```
