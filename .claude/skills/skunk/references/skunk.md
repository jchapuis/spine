# Skunk — Functional PostgreSQL Reference (Scala)

Sources:
- https://typelevel.org/skunk/tutorial/Setup.html
- https://typelevel.org/skunk/tutorial/Query.html
- https://typelevel.org/skunk/tutorial/Command.html
- https://typelevel.org/skunk/tutorial/Transactions.html
- https://github.com/iltotore/iron/blob/main/docs/_docs/modules/skunk.md

## Table of Contents
- [Session setup](#session-setup)
- [Codec catalogue](#codec-catalogue)
- [Codec composition](#codec-composition)
- [Queries](#queries)
- [Commands (DML)](#commands-dml)
- [Prepared statements and streaming](#prepared-statements-and-streaming)
- [Transactions](#transactions)
- [Fragment composition](#fragment-composition)
- [Iron integration](#iron-integration)
- [Repository pattern](#repository-pattern)
- [PostGIS notes](#postgis-notes)

---

## Session setup

```scala
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*
import cats.effect.{IO, Resource}
import natchez.Trace.Implicits.noop  // or real tracer

// Session pool (production):
val pool: Resource[IO, Resource[IO, Session[IO]]] =
  Session.pooled[IO](
    host     = "localhost",
    port     = 5432,
    user     = "postgres",
    database = "mydb",
    password = Some("secret"),
    max      = 10
  )

// Single session (tests):
val session: Resource[IO, Session[IO]] =
  Session.single[IO](
    host     = "localhost",
    port     = 5432,
    user     = "postgres",
    database = "mydb"
  )
```

---

## Codec catalogue

```scala
// Numeric
int2, int4, int8
float4, float8
numeric

// Text
varchar        // varchar (variable length)
varchar(n)     // varchar(n)
text           // text
bpchar(n)      // char(n)

// Boolean
bool

// Date/Time
date
time
timestamp
timestamptz

// UUID
uuid

// JSON
json           // raw Json as String
jsonb

// Arrays
_int4          // int4[]
_text          // text[]

// Nullable columns — wrap any codec:
int4.opt       // Codec[Option[Int]]
varchar.opt    // Codec[Option[String]]
```

---

## Codec composition

```scala
// Product with *: (Scala 3 tuple syntax):
val codec: Codec[(String, Int)] = varchar *: int4

// Map to case class:
case class User(name: String, age: Int)
val userCodec: Codec[User] = (varchar *: int4).to[User]

// Three or more fields:
case class Point(x: Double, y: Double, label: String)
val pointCodec: Codec[Point] = (float8 *: float8 *: varchar).to[Point]

// Nullable field:
case class Profile(name: String, bio: Option[String])
val profileCodec: Codec[Profile] = (varchar *: text.opt).to[Profile]
```

---

## Queries

```scala
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

// Parameterless query:
val allUsers: Query[Void, User] =
  sql"SELECT name, age FROM users"
    .query(userCodec)

// Parameterised query (single param):
val userById: Query[Int, User] =
  sql"SELECT name, age FROM users WHERE id = $int4"
    .query(userCodec)

// Multiple params — use *: for input codec too:
val userByNameAge: Query[(String, Int), User] =
  sql"SELECT name, age FROM users WHERE name = $varchar AND age > $int4"
    .query(userCodec)

// Unique result (throws if 0 or >1 rows):
session.unique(allUsers)            // IO[User] — use only if exactly one row guaranteed

// Optional result:
session.option(userById)(42)        // IO[Option[User]]

// All rows as list (avoid on large tables):
session.execute(allUsers)           // IO[List[User]]
```

---

## Commands (DML)

```scala
val insertUser: Command[(String, Int)] =
  sql"INSERT INTO users (name, age) VALUES ($varchar, $int4)".command

val deleteUser: Command[Int] =
  sql"DELETE FROM users WHERE id = $int4".command

val updateAge: Command[(Int, Int)] =
  sql"UPDATE users SET age = $int4 WHERE id = $int4".command

// Execute:
session.execute(insertUser)(("Alice", 30))   // IO[Completion]
session.execute(deleteUser)(42)
```

---

## Prepared statements and streaming

```scala
// Prepare once, use many times:
session.prepare(userById).use { ps =>
  ps.stream(42, chunkSize = 64)   // fs2.Stream[IO, User]
    .evalTap(u => IO.println(u))
    .compile
    .drain
}

// Or single result:
session.prepare(userById).use { ps =>
  ps.unique(42)    // IO[User]
  ps.option(42)    // IO[Option[User]]
}

// Batch insert with pipe:
val insertMany: Pipe[IO, (String, Int), Nothing] =
  session.pipe(insertUser)

Stream.emits(users)
  .through(insertMany)
  .compile
  .drain
```

---

## Transactions

```scala
// All statements in a transaction:
session.transaction.use { tx =>
  for
    _ <- session.execute(deleteUser)(42)
    _ <- session.execute(insertUser)(("Bob", 25))
  yield ()
}

// Savepoints:
session.transaction.use { tx =>
  for
    sp <- tx.savepoint
    _  <- session.execute(insertUser)(("Alice", 30))
    _  <- tx.rollback(sp)   // roll back to savepoint on error
  yield ()
}
```

---

## Fragment composition

```scala
import skunk.implicits.*

// Build dynamic WHERE clauses with fragments:
val baseQuery = sql"SELECT name, age FROM users"

def withFilter(minAge: Option[Int]): Fragment[Void] =
  minAge match
    case Some(age) => sql"$baseQuery WHERE age > $int4"  // doesn't compose cleanly like this
    case None      => baseQuery

// Better: use AppliedFragment for dynamic conditions:
import skunk.AppliedFragment

val select = sql"SELECT name, age FROM users".apply(Void)
val cond   = sql"WHERE age > $int4".apply(30)

val full: AppliedFragment = select |+| cond
// execute with session.execute(full.fragment)(full.argument)
```

---

## Iron integration

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.given   // brings .refined extension into scope
import skunk.codec.all.*

// Inline refinement on any codec:
type Username = String :| Not[Blank]
val usernameCodec: Codec[Username] = varchar.refined

// Opaque newtype with dedicated codec:
type UserId = UserId.T
object UserId extends RefinedType[Int, Positive]:
  given codec: Codec[UserId] = int4.refined[Positive]

// Case class with mixed refined fields:
case class User(name: String :| Not[Blank], age: Int :| Positive)
given Codec[User] = (varchar.refined[Not[Blank]] *: int4.refined[Positive]).to[User]
```

---

## Repository pattern

```scala
import cats.effect.{Async, Resource, MonadCancelThrow}
import skunk.Session

// Algebra (shared/server module):
trait UserRepo[F[_]]:
  def find(id: UserId): F[Option[User]]
  def insert(user: User): F[Unit]
  def list: F[List[User]]

// Implementation takes a Session or pool checkout:
object UserRepo:
  def apply[F[_]: MonadCancelThrow](session: Session[F]): UserRepo[F] =
    new:
      def find(id: UserId): F[Option[User]] =
        session.prepare(findById).use(_.option(id))

      def insert(user: User): F[Unit] =
        session.execute(insertCmd)((user.name, user.age)).void

      def list: F[List[User]] =
        session.execute(allUsers)
```

---

## PostGIS notes

```scala
// PostGIS geometry types are not in skunk-core — use text/varchar and parse in Scala,
// or define custom codecs using skunk's Codec.simple:
import org.locationtech.jts.geom.{Geometry, Point}
import org.locationtech.jts.io.WKTReader

val geometryCodec: Codec[Geometry] =
  Codec.simple[Geometry](
    encode = g => Right(g.toText),
    decode = s => Either.catchNonFatal(WKTReader().read(s)).leftMap(_.getMessage),
    oid    = skunk.data.Type.text.oid
  )

// ST_Transform and PostGIS functions go in the SQL string:
sql"SELECT ST_AsText(ST_Transform(location, 4326)) FROM sites WHERE id = $int4"
  .query(geometryCodec)
```
