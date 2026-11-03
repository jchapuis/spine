# Synthetic data for the mechanical-rule groups

You write realistic Scala 3 code changes that teach a small decision model (kev) the compliance gate's mechanical
rules. **You do not label anything**: `label_mechanical.py` runs the real gate (ripgrep + Scalafix) on every example
and derives the labels. Your job is variety and realism, with the right mix of violations, near misses and clean code.

The groups, their rules and the exact question the model is asked are in `mechanical-groups.json`. Read it first,
then read each rule's `message` and `pattern` in its skill's `SKILL.md` frontmatter (`mechanical_checks:`) and the
gate's fixtures in `.claude/skills/compliance-gate/fixtures/<rule-id>/{bad,good}.scala`, so you know exactly what
fires and what doesn't.

## Format

Write plain text files of blocks. Whole files before and after, never hunks (Scalafix must parse them):

```
=== EDIT
note: one short line saying what you intended (for humans; ignored by training)
--- file: server/src/main/scala/com/acme/billing/InvoiceService.scala
--- before
package com.acme.billing
...whole file before the edit; leave empty (nothing between the markers) for a new file...
--- after
package com.acme.billing
...whole file after the edit...
=== END
```

`=== EDIT` has exactly one `--- file:`. `=== TURN` (only for `mech-duplication`) has 2–3 `--- file:` sections, each
with its own before/after: the whole change of one agent turn.

## Each file

- 10–40 lines, valid Scala 3 that would parse: package line, imports, braceless syntax, cats-effect / http4s / tapir /
  skunk / fs2 / iron, Laminar on the frontend, munit in tests.
- Realistic paths: `server/src/main/scala/com/<org>/<domain>/...`, `server/src/test/scala/...`,
  `app/src/main/scala/...` for Laminar code (frontend files must `import com.raquo.laminar.api.L.*`). Vary the org and
  domain (billing, routing, weather, inventory, auth, maps, scheduling, payments, ...).
- Apart from what you deliberately put in, follow the project style so you don't add accidental violations: no
  comments, no `final`, no `var`, braceless lambdas (`.map: x =>`), `if … then … else`, no type ascription on
  private/local vals, `enum` for closed ADTs, specific `cats.syntax.<module>.*` imports.
- The edit is a plausible step: add a method, change a call, fix a bug, extract a helper, add a test, remove a
  violation. Usually 1–8 lines change; the rest of the file is context.

## Mix per group (about 120 blocks per group; 100 TURN blocks for mech-duplication)

- **~55% positives:** the edit adds a violation of one of the group's rules. Cover **every** rule in the group roughly
  evenly, in varied forms (different receivers, positions, nesting, multi-line expressions).
- **~25% near misses that must NOT fire:** the compliant idiom the rule asks for (e.g. `Option`/`raiseError` instead of
  `throw`, `parEvalMap(8)`, `xs.combineAll`), the keyword inside a string literal or a longer identifier
  (`"throw"`, `throwable`, `nullable`), a violation that already sat in the *before* file and the edit touches other
  lines, a path the rule excludes (tests where the rule allows it), and **fix edits** that remove a violation.
- **~20% clean edits** with nothing of this group in them.

Exact positive/negative counts don't matter, the gate decides; aim for the mix. Never copy fixtures verbatim, and
don't repeat the same snippet with renamed identifiers.

## Output

Write to `.abide/finetune/synthetic/mech/<group-id>/part-N.txt`, about 30 blocks per part file. When done, run
`python3 .abide/finetune/label_mechanical.py '.abide/finetune/synthetic/mech/<group-id>/part-*.txt' /tmp/<group-id>.jsonl`
from the spine root and report its positives count. If fewer than ~40% of your blocks came out positive for your
group, or a rule of your group never fired, add blocks for it until it does, and say what was off.
