---
name: waypoint
description: Waypoint client-side routing for Scala.js/Laminar. Use when defining page ADTs, URL patterns, router setup, SplitRender-based view dispatch, or programmatic navigation in Laminar applications.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['com\.raquo\.waypoint']

mechanical_checks:
  - id: waypoint.current-page-signal-map
    tool: ripgrep
    severity: error
    pattern: '\bcurrentPageSignal\s*\.\s*map\b'
    files_must_import: ['com\.raquo\.waypoint']
    message: 'Render the page tree with `SplitRender`, not `currentPageSignal.map` — keeps the rendered subtree stable per Page case'
    references: ['waypoint#rendering-with-splitrender']
---

# Waypoint — Client-Side Routing (Scala.js)

## Quick start
- Define a `sealed trait Page` hierarchy with one case class/object per route.
- Build routes with `Route.static`, `Route[Page, Args]`, or `Route.withQuery`.
- Use the URL DSL: `root / "segment" / segment[Int] / endOfSegments`.
- Create a `Router[Page]` with all routes, a `getPageTitle` function, and ser/deserialization for history.
- Render with `SplitRender[Page, HtmlElement](router.currentPageSignal)` — never `.map` the signal directly.
- Navigate programmatically with `router.pushState(page)`.
- Read `references/waypoint.md` for URL DSL, route variants, SplitRender patterns, and navigation.

## Workflow
1. Define `sealed trait Page` and all `case class`/`case object` subtypes.
2. Define one `Route` per page using the URL DSL.
3. Instantiate `Router[Page](routes, getPageTitle, serializePage, deserializePage, owner = unsafeWindowOwner)`.
4. Render using `SplitRender` — use `.collectSignal[SubPage]` for parameterized pages, `.collectStatic` for singletons.
5. Navigate using `router.pushState(page)` or expose an `Observer[Page]` that calls it.

## Key rules
- **`SplitRender` is mandatory** for routing signal → DOM: it reuses elements across navigations that stay on the same page type. Using `.map` recreates the entire subtree on every navigation.
- **Parameterized pages get a `Signal`** in `.collectSignal` — this means the child component receives a stable reactive signal that updates without remounting.
- **URL DSL is type-safe** — `segment[Int]` encodes/decodes automatically; use the matching `encode`/`decode` lambdas that mirror each other.
- **`endOfSegments`** — always terminate patterns with `endOfSegments` to avoid prefix matches swallowing unmatched routes.
- **One router per app** — pass the router down as a `given`/parameter; never create more than one `Router` instance.
- **`router.currentPageSignal`** is the source of truth; derive all page-dependent state from it rather than storing page in a separate `Var`.

## Output expectations
- Page hierarchy is a sealed `trait`/`enum`; exhaustive match is enforced by the compiler.
- `SplitRender` handles all page-to-element dispatch; no manual `match` + `.map` on `currentPageSignal`.
- Navigation is always via `router.pushState`; browser history is managed automatically.

## References
- Load `references/waypoint.md` for URL DSL, route types, router setup, SplitRender, and navigation patterns.
