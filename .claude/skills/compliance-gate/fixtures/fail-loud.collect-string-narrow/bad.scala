def stringValues(values: List[Any]): List[String] =
  values.collect { case value: String => value }

def stringProperty(properties: Map[String, Any], key: String): Option[String] =
  properties
    .get(key)
    .collect:
      case value: String => value
