import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

opaque type Code = String :| Match["[A-Z]+"]

val sample: Code = Code("OK")
