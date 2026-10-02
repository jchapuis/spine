package compliancegate

object ImportMatch:
  def anyMatches(patterns: List[String], head: String): Boolean =
    patterns.exists(pattern => pattern.r.findFirstIn(head).isDefined)
