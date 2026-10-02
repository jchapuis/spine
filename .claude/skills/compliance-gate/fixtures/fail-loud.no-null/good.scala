def lookup(name: String): Option[String] =
  if name.isEmpty then None
  else Some(s"Hello, $name")
