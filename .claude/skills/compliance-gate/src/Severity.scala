package compliancegate

import upickle.default.ReadWriter

enum Severity derives ReadWriter:
  case Error, Warning, Info

object Severity:
  def parse(string: String): Either[GateError, Severity] = string.toLowerCase match
    case "error"   => Right(Error)
    case "warning" => Right(Warning)
    case "info"    => Right(Info)
    case other     => Left(GateError.InvalidSeverity(other))

  extension (severity: Severity)
    def label: String = severity match
      case Error   => "error"
      case Warning => "warning"
      case Info    => "info"

    def blocks: Boolean = severity == Error
