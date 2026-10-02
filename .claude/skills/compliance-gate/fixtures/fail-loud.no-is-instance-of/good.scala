def hasLabel(value: Any): Boolean = value match
  case _: String => true
  case _         => false
