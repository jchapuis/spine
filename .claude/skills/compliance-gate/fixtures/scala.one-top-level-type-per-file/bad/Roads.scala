package compliancegate.fixtures

enum Surface:
  case Paved, Gravel

case class RoadSegment(surface: Surface, lengthMeters: Double)

trait RoadClassifier:
  def classify(segment: RoadSegment): Surface

type Grade = Double
