package compliancegate

object Report:
  def human(findings: List[Finding]): String =
    if findings.isEmpty then "compliance-gate: clean"
    else
      val summary = Summary.from(findings)
      val header = s"compliance-gate: ${summary.error} error, ${summary.warning} warning, ${summary.info} info"
      val body = findings.sortBy(finding => (finding.file, finding.line, finding.rule)).map(renderFinding).mkString("\n\n")
      s"$header\n\n$body"

  def json(findings: List[Finding]): String =
    val report = GateReport(findings)
    upickle.default.write(report, indent = 2)

  private def renderFinding(finding: Finding) =
    val columnSuffix = finding.column.fold("")(value => s":$value")
    val refSuffix = finding.references match
      case Nil  => ""
      case refs => s"\n    refs: ${refs.mkString(", ")}"
    s"""${finding.file}:${finding.line}$columnSuffix
       |  [${finding.severity.label}] ${finding.rule}
       |    ${finding.message}
       |    skill: ${finding.skill}  tool: ${finding.tool}$refSuffix""".stripMargin
