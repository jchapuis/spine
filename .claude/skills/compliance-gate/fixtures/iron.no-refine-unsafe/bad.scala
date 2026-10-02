import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength

val nonEmpty = "hello".refineUnsafe[MinLength[1]]
