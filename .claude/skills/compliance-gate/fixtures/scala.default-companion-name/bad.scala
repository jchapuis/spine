case class Config(host: String, port: Int)

object Config:
  val Default = Config(host = "localhost", port = 8080)
