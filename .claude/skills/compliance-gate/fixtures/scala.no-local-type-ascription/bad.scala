object Service:
  def render(name: String): String =
    val title: String = name.toUpperCase
    val width: Int = title.length
    def banner(text: String): String = s"== $text =="
    banner(title) + s" ($width)"

extension (text: String)
  def shout: String =
    def exclaim(value: String): String = s"$value!"
    exclaim(text.toUpperCase)
  def whisper: String = text.toLowerCase
