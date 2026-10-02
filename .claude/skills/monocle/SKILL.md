---
name: monocle
description: Monocle optics for Scala immutable data. Use when updating nested case class fields, traversing optional or collection fields, composing lenses/prisms, or replacing manual copy chains in domain or UI state updates.

triggers:
  - file_patterns: ['**/*.scala', '**/*.sc']
    imports: ['monocle\.', 'monocle\.syntax']

---

# Monocle — Optics (Scala 3)

## Quick start
- Use the `focus` macro for in-place updates: `obj.focus(_.field.nested).modify(f)`.
- Use `focus(_.optField.some)` to modify inside `Option`; the modification is a no-op on `None`.
- Import `monocle.syntax.all.*` to bring `focus` into scope.
- Use `GenLens[A](_.field)` to extract a named `Lens` value for reuse.
- Prefer `focus` and `GenLens` over hand-written `Lens.apply` constructors.
- Read `references/monocle.md` for optic types, composition, collection traversals, and Prism patterns.

## Workflow
1. Identify the update path through the data structure.
2. Choose the optic: `Lens` for required fields, `Optional`/`Prism` for optional/sum-type fields, `Traversal` for collections.
3. Use `focus` for one-off updates; extract a named `Lens`/`Optional` for reuse.
4. Compose optics with `.andThen` to chain nested paths.
5. Apply with `.replace(value)` (set) or `.modify(f)` (transform).

## Key rules
- **`focus` is preferred** over explicit `Lens` construction for simple field updates.
- **Composition with `.andThen`** — chain optics; the result type narrows automatically (Lens + Prism = Optional).
- **`.some` for Option fields** — `.focus(_.maybeField.some)` gives an `Optional`; no-op on `None`.
- **`.index(i)` for collections** — use for map or list index access; returns `Optional` (no-op for missing index).
- **No `.get` on Optional without `getOption`** — `Optional` and `Prism` do not have `.get`; use `.getOption` which returns `Option[A]`.
- **Prefer pure optics over manual `.copy` chains** — three or more nested `.copy` chains should become a composed optic.

## Output expectations
- Nested updates return a new immutable value (no mutation).
- Optic composition uses `.andThen` and produces correctly-typed combined optics.
- Optional traversals are safe: absent targets produce identity transformations.

## References
- Load `references/monocle.md` for Lens, Prism, Optional, Traversal, Iso patterns and composition examples.
