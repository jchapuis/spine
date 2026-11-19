trait DirectRouteHome:
  def routeToFinish(position: GeoPoint, finish: GeoPoint)(using RoutingProfile): IO[Option[DirectRoute]]
