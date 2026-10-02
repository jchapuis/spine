package compliancegate

import upickle.default.ReadWriter

case class Finding(
    file: String,
    line: Int,
    column: Option[Int],
    skill: String,
    rule: String,
    severity: Severity,
    message: String,
    tool: String,
    fixable: Boolean = false,
    references: List[String] = List.empty
) derives ReadWriter

case class Summary(error: Int, warning: Int, info: Int) derives ReadWriter

object Summary:
  def from(findings: List[Finding]): Summary =
    Summary(
      error = findings.count(_.severity == Severity.Error),
      warning = findings.count(_.severity == Severity.Warning),
      info = findings.count(_.severity == Severity.Info)
    )

case class GateReport(version: Int, findings: List[Finding], summary: Summary) derives ReadWriter

object GateReport:
  val SchemaVersion = 1
  def apply(findings: List[Finding]): GateReport =
    GateReport(SchemaVersion, findings, Summary.from(findings))
