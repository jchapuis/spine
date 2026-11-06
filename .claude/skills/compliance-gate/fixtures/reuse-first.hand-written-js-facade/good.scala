import org.scalajs.dom

def encoded(input: String) = new dom.TextEncoder().encode(input)
