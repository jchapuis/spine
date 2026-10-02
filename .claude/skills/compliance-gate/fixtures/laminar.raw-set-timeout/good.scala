import com.raquo.laminar.api.L.*
import scala.concurrent.duration.*

def delayed(action: () => Unit): EventStream[Unit] =
  EventStream.fromValue(()).delay(500).map(_ => action())
