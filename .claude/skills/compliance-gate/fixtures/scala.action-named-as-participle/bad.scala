trait DirectRouteHome:
  def routed(position: GeoPoint, finish: GeoPoint)(using RoutingProfile): IO[Option[DirectRoute]]
