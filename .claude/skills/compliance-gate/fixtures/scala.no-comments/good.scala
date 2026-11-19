//> using scala 3.7.2
//> using dep org.typelevel::cats-core:2.13.0

def reportTotal(values: List[Int]): Int = values.sum

def reportHeader: String = "Report"

case class ReportConfig(
    /** Rows per page: one screen on a phone without scrolling. */
    pageSize: Int = 20
)
