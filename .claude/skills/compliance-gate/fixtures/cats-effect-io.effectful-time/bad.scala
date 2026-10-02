import java.time.Instant

object Stamps:
  def stamp(): Instant = Instant.now()
  def elapsedMillis(start: Long): Long = System.currentTimeMillis() - start
