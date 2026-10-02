def lookup(name: String): String =
  if name.isEmpty then null
  else s"Hello, $name"
