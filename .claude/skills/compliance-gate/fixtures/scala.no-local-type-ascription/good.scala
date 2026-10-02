object Service:
  def render(name: String): String =
    val title = name.toUpperCase
    val width = title.length
    def banner(text: String) = s"== $text =="
    banner(title) + s" ($width)"

extension (text: String)
  def shout: String =
    def exclaim(value: String) = s"$value!"
    exclaim(text.toUpperCase)
  def whisper: String = text.toLowerCase
