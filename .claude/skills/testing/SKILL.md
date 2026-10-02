---
name: testing
description: Testing conventions for Scala projects (MUnit + ScalaCheck + CatsEffectSuite). Use when writing, modifying, or reviewing any test — covers property-based testing, generator reuse, sampleSome fixtures, static expected values, magic numbers in tests, mock hygiene, and integration-test discipline.

triggers:
  - file_patterns:
      - '**/src/test/**/*.scala'
      - '**/src/it/**/*.scala'
      - '**/*Suite.scala'
      - '**/*Spec.scala'
---

# Testing — MUnit + ScalaCheck Conventions

## Quick start
- **MUnit** + **CatsEffectSuite** for `IO`-based tests.
- **Assert with `assertEquals(actual, expected)`**, not `assert(actual == expected)` — MUnit shows a value diff on failure, and it reads as a comparison rather than an opaque boolean.
- **Expected values are static.** A test asserts against literals or sampled data — it never re-computes the expectation with the same kind of logic the code under test uses.
- **No magic numbers.** Values the assertion doesn't depend on come from generators (`sampleSome`); values it does depend on get a name.
- **Properties for genuine invariants, examples for everything else** — don't overdo either direction.
- **Reuse generators** — search the shared generators object before defining one; never redefine an existing `gen*` / `Arbitrary`.
- **Extract + property-test travel together**: pure logic worth testing is worth extracting; extract the helper AND cover it with properties in the same change.
- **No comments in tests** — the suite's structure and test names are the documentation.
- Prefer the mocking library over hand-rolled `Fake*` classes; shrink giant data mocks with generators + `.copy`.

## Property-based testing

When the assertion is a natural invariant — "preserved on failure", "round-trip", "monotonic", "idempotent", "length/order/bounds hold for any input" — and the input has a usable `Arbitrary`, prefer `forAll` over hand-picked examples. Properties catch inputs you didn't think of and self-document the invariant.

- **Scope tests to the invariant, not the instance.** A test asserting "when X = specific-value, Y holds" usually hides "for any X in [valid-set], Y holds". Parameterize over the dimension — MUnit `test(...)` per case, ScalaCheck `forAll`, or table-driven cases. When the dimension is a closed refined set, drive the test from the companion's `supported` list (see `iron`'s closed-set rule).
- **Don't overdo it.** A property earns its place when the invariant is real; an example test with well-named static data beats a contrived property that restates the implementation.
- **Extraction pairs with properties.** When review-worthy pure logic sits inline in a component, algebra, or pipeline (a computation, an accessor, a formatting rule), extract it to where it belongs (see `factor-out`, `scala#cohesive-sub-packages`) and give the extracted helper property coverage in the same change. Extracting without testing, or testing inline logic without extracting, are both half-done.

## Generators and fixtures

- **Build fixtures from generators.** Keep `gen*` / `Arbitrary` instances in a shared generators object; in a test, sample one (`sampleSome`) and `.copy(...)` only the fields the assertion depends on, leaving the rest sampled. Hand-built literals duplicate across suites and over-specify irrelevant fields (see `factor-out`).
- **Never redefine an existing generator.** Before writing `genOrder` or `Arbitrary[Order]`, search the shared generators object and sibling suites — redefinition drifts and duplicates.
- **Sampled where irrelevant, named where relevant.** A raw `42.7` in a test body is either irrelevant (→ sample it) or load-bearing (→ name it so the assertion reads).
- **Keep fixtures hermetic.** Ship small fixture data under `src/test/resources` / `src/it/resources` and serve it locally — never point a test at a remote URL or bucket, which ties the suite to network reachability and to a blob continuing to exist.

## Static expectations

The expected side of an assertion is data, not computation. Re-deriving the expectation in the test (unit conversions, aggregations, the same formula the production code applies) proves nothing — a shared bug passes both sides.

```scala
// Bad — the test re-computes what the code under test computes
val expected = intervals.map(_.duration.toSeconds).sum / 3600.0
assertEquals(plan.totalHours, expected)

// Good — static expectation from a fixture the test controls
val plan = samplePlan.copy(intervals = List(interval(30.minutes), interval(90.minutes)))
assertEquals(plan.totalHours, 2.0)
```

## Mock hygiene

- Prefer real implementations, then the project's mocking library; hand-rolled `Fake*` / `Stub*` classes are a last resort — they accrete state and drift from the trait.
- **A giant mock is a smell** — either the trait is too wide (see `server-architecture`) or the test is over-specifying; feed data mocks from generators + `.copy` instead of dozens of hand-written fields.
- **Don't bend production code to a test-tooling limitation** (e.g. a mock library that can't handle a construct) — fix or upgrade the tooling, or open an issue. Tests adapt to the code, not the reverse (see `fail-loud`).
- Prefer real infrastructure over mocks in integration tests — mocking can mask deployment issues.

## Integration tests

- Integration tests run sequentially (`parallelExecution := false`) to avoid database conflicts.
- They use the `it:test` SBT config and require live backing infrastructure (database, containers) — never a shared remote environment.
- A test that consumes paid external credits (live LLM calls, metered APIs) must be opt-in, never part of the default suite.

## Red flags — stop and rework
| Excuse | Reality |
|---|---|
| "Computing the expectation keeps the test DRY" | A shared formula proves both sides wrong together; static data is the point. |
| "These numbers are obvious from context" | A bare literal is either irrelevant (sample it) or load-bearing (name it) — never bare. |
| "A quick local generator is faster than searching" | The shared generators object exists; a redefinition drifts on the next model change. |
| "The logic is small, testing it inline is enough" | Inline logic can't be property-tested; extract + `forAll` in the same change. |
| "A property here would be elegant" (restating the impl) | A property that mirrors the implementation asserts nothing; use named examples. |
| "The Fake class gives me full control" | Full control of a drifting copy; the mocking library tracks the trait for free. |

## Cross-references
- `scala` — naming, braceless syntax, and general conventions apply to test code too.
- `factor-out` — shared fixtures/generators placement; duplication across suites.
- `iron` — closed-set `supported` lists as generator sources.
- `fail-loud` — never bend production code to test tooling.
