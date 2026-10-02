# Synthetic training data for the spine's abide rubric

You are writing labelled examples that teach a small decision model (kev) to judge Scala 3 code changes against six
rules. At run time the model sees exactly one thing: a code diff from a coding agent's edit, plus one yes/no question
per rule. Your examples must look like those diffs and be labelled correctly.

## The six rules (short key → what "true" means)

Edit-phase rules (asked about ONE file's diff after each edit):

- `fail-loud` — "Does this change hide a failure instead of surfacing it: a getOrElse(<default>) that masks a
  missing-data case, a recover/handleError that swallows a bug or a non-transient external failure, or a side effect
  inside a for-comprehension that still runs after an upstream validated step failed?"
- `service-dep` — "Does a Service depend directly on another concrete Service (holding it as a constructor field or
  calling it), instead of depending on an algebra/trait that the other service implements?"
- `logic` — "Is business/orchestration logic placed where it does not belong: concrete multi-step logic inlined in a
  Service method, or an algebra shipped as an object with concrete logic and no trait, rather than behind a trait / in
  a Logic trait?"
- `names` — "Does this change introduce an identifier a newcomer could not read: an abbreviation (avgX, reqY, a
  single-letter non-idiomatic parameter), or a metaphor/jargon name (ask, ladder, rung, strut, filler) where a plain
  domain name exists?"
- `cats` — "Does this change use an imperative or wrong Cats combinator: a flatMap/map chain over independent effects
  that should be mapN, or a fold accumulator that should be foldMap?"

Turn-phase rule (asked once about the WHOLE change, possibly several files):

- `dup` — "Does this change define the same thing twice — a constant, a test fixture, or a 2-3 line idiom repeated
  across the diff — that should be lifted to a single definition or an extension method?"

## Labelling rules (read carefully — label quality is the whole point)

1. **Judge only what the change ADDS** (the `+` lines). A violation that sits only in removed (`-`) or context (` `)
   lines is `0`. A diff that *fixes* a violation (bad code removed, good code added) is `0` for that rule — these
   "fix" diffs are valuable, include them.
2. Label **every** rule for every edit block, not just the one you targeted. Keep untargeted rules clean unless you
   deliberately combine two violations.
3. Be strict about the boundary. Hard negatives (label `0`) you MUST include often:
   - fail-loud: `.toRight(err)`, `.liftTo[F]`, `.raiseError`, `getOrElse(throw ...)`, an Option/Either returned to the
     caller, `.fold` mapping a missing entity to an explicit 404 at the HTTP boundary, `recoverWith` on a transient
     network error that retries with backoff and re-raises after N attempts, a default that the type documents as
     legitimate (e.g. an `Option[PageSize]` query param defaulting to the documented page size constant).
   - service-dep: a Service holding algebra/repository **traits**, Routes depending on a Service (routes → service is
     fine), a Logic trait depending on an algebra, a Service holding an HTTP client trait.
   - logic: an algebra as a `trait` with its implementation in the companion `apply`, orchestration inside a dedicated
     `*Logic` trait, a plain `object` of pure helper functions that is not an algebra (e.g. `object Geodesy { def haversine ... }`).
   - names: idiomatic short lambda params in one-liners (`_`, `xs.map(x => x * 2)`, `(a, b) => a + b`), standard
     abbreviations (`id`, `url`, `json`, `http`, `db`, `uuid`), established domain words (heading, bearing, leg,
     waypoint, node, edge, zone, grade, FTP, SKU, invoice).
   - cats: `flatMap` where the second effect DEPENDS on the first's result (that is correct), `for`/`yield` for
     dependent steps, `mapN`/`parMapN`, `traverse`, `foldMap`, `combineAll`.
   - dup: two values that look alike but mean different things, reusing an existing named constant, one shared fixture
     used by two tests, an extension method introduced and called twice.
4. Positives should be varied and realistic, not cartoonish. Mix obvious and subtle ones.

## Clarifications (v2 — these sharpen, never contradict, the rules above)

- **fail-loud and configuration.** A *required* setting (config key, env var, CLI arg, feature input) that silently
  falls back to a literal when missing is `1`: `sys.env.getOrElse("DB_PORT", "5432")`,
  `conf.get("timeout").map(_.toInt).getOrElse(30)`, `.handleError(_ => defaultConfig)` on a config parse. It is `0` when
  the setting is genuinely optional and the default is a named, documented constant (`PageSize.Default`), or when the
  missing value fails the startup (`ConfigSource.default.loadOrThrow`, `.toRight(MissingSetting("DB_PORT"))`).
  Retries: a retry that drops the failure with no visible bound is `1`; an attempts counter, `maxRetries`, or a policy
  that re-raises after N tries is `0`. A default for an aggregate of nothing (`Nil`, `Money.zero` for an empty sum) is `0`.
- **names.** `1` for vowel-dropped or truncated words (`cnt`, `usrRepo`, `calcTtl`, `rslt`, `tmpRes`, `prv`), vague
  verbs/nouns (`doIt`, `handleStuff`, `data2`, `info`), single letters for parameters or multi-line lambdas
  (`def merge(a: Order, b: Order)`, `xs.map { o => ...10 lines... }`), and metaphors for plain concepts (ladder, rung,
  strut, filler, ask, chain, footprint, claim). `0` for type parameters (`A`, `F[_]`), `_`, short params in one-line
  lambdas, `(acc, x)` in a fold, `e`/`t` for a caught throwable, standard abbreviations (`id`, `url`, `json`, `db`, `uuid`,
  `http`, `ms` in a unit-suffixed name) and established domain words. One bad name in an otherwise clean diff is `1`.
- **dup and mirrored modules.** Code or documentation repeated across modules that must stay separate is `0`: the same
  change applied to `akka-runtime/` and `pekko-runtime/`, to `scala-2/` and `scala-3/` sources, to `jvm/` and `js/`
  platform dirs, or to `v1/` and `v2/` API packages. Repeated test literals or fixtures across tests in the same suite
  (`Money(209)` built in two tests) are `1`; so are near-identical helper methods added to two companion objects in
  the same module.

## Style of the code

- Scala 3, braceless syntax, cats-effect (`IO` or `F[_]: Async`/`Sync`), http4s, tapir, skunk, fs2, iron refined
  types, Laminar (Scala.js) on the frontend, munit + ScalaCheck in tests. Realistic package paths like
  `server/src/main/scala/com/acme/billing/InvoiceService.scala` or `app/src/test/scala/...`.
- Invent everything. Do not copy code from any real repository.
- Edit diffs: 3–40 lines, one file, one or two hunks, with a few context lines. Turn diffs: 1–3 files, 10–80 lines total.
- Vary: new files, small modifications, renames, test files, refactors, frontend files, config-ish code.

## Output format (plain text blocks — NOT JSON)

Write blocks exactly like this. The diff lines are copied verbatim, so every diff line must start with `@@`, a space,
`+`, `-`, `---` or `+++`. No markdown fences.

```
=== EDIT server/src/main/scala/com/acme/orders/PricingService.scala
labels: fail-loud=1 service-dep=0 logic=0 names=0 cats=0
note: getOrElse(0) hides a missing price
@@ -12,6 +12,9 @@ final class PricingService[F[_]: Sync](prices: PriceRepository[F]):
   def priceOf(sku: Sku): F[Money] =
-    prices.find(sku).flatMap(_.liftTo[F](PriceMissing(sku)))
+    prices.find(sku).map(_.getOrElse(Money.zero))
=== END
```

```
=== TURN
labels: dup=1
note: same 30s timeout constant defined in two clients
--- a/server/src/main/scala/com/acme/mail/MailClient.scala
+++ b/server/src/main/scala/com/acme/mail/MailClient.scala
@@ -3,4 +3,5 @@ object MailClient:
+  val RequestTimeout = 30.seconds
--- a/server/src/main/scala/com/acme/sms/SmsClient.scala
+++ b/server/src/main/scala/com/acme/sms/SmsClient.scala
@@ -3,4 +3,5 @@ object SmsClient:
+  val RequestTimeout = 30.seconds
=== END
```

`labels:` must list all five edit keys (EDIT) or exactly `dup` (TURN), each `=0` or `=1`. `note:` is a few words for
the reviewer; the model never sees it.

## Quotas (per generator)

- **115 EDIT blocks**: each of the five rules positive in about 22–28 blocks; about 40 blocks with every label 0, of
  which ~15 are hard negatives from the list above and ~10 are "fix" diffs; about 5 blocks with two violations.
- **40 TURN blocks**: 20 with `dup=1`, 20 with `dup=0` (at least 8 of those hard negatives).
- Write in parts of ~25–30 blocks to files `part-1.txt`, `part-2.txt`, … in your output directory, and count as you go.
