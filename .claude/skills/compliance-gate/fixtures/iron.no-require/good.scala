import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

type Balance = Balance.T
object Balance extends RefinedType[Double, GreaterEqual[0.0]]

type Arn = Arn.T
object Arn extends RefinedType[String, StartWith["arn:"]]

case class Account(balance: Balance, arn: Arn)

object Loader:
  def load(config: Config): Either[String, Account] =
    for
      balance <- Balance.either(config.require[Double]("balance"))
      arn <- Arn.either(config.require[String]("arn"))
    yield Account(balance, arn)

trait Config:
  def require[A](key: String): A
