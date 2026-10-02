def label(value: Any): Option[String] =
  value match
    case text: String => Some(text)
    case _            => None
