import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.string.Match

opaque type Slug = String :| Match["[a-z-]+"]

val sample: Slug = Slug.applyUnsafe("ok-value")
