package com.example.server.model

import cats.data.NonEmptyList
import com.example.server.model.reroute.RejoinPoint
import com.example.shared.model.route.EnrichedRouteSegment

case class ScoredRejoin(rejoin: RejoinPoint, segments: NonEmptyList[EnrichedRouteSegment])
