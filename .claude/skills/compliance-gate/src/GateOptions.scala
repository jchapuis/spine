package compliancegate

case class DiffSelection(hook: Option[String], diff: Option[String], base: Option[String])

case class GateRoots(projectRoot: Option[String], skillsRoot: Option[String])

case class GateOptions(
    roots: GateRoots,
    selection: DiffSelection,
    skills: Option[String],
    json: Boolean,
    runScalafmt: Boolean,
    runScalafix: Boolean,
    selfTest: Boolean
)
