Always use:
- **serena mcp to edit code, not the shell.** `replace_symbol_body`, `insert_after_symbol` / `insert_before_symbol`, `rename_symbol` (it rewrites the references too), `safe_delete_symbol`. Bash is for *running* things — sbt, git, curl — not for rewriting Scala; reach for `sed`/`perl`/heredocs or a whole-file `Write` only when the target is not a symbol (`.conf`, markdown, JSON), and prefer a per-file `replace_content` even then. Call `initial_instructions` once before the first serena call. Its index follows the project root, so the worktree caveat below applies to it too. Two traps:
  - `replace_content` in `mode: regex` inserts `$1` **literally** — no capture-group backrefs. One literal call per site instead.
  - `replace_in_files` ignores `relative_paths` and rewrites the whole repo. Never use it for a scoped sweep.
- metals mcp for Scala navigation (get-usages, inspect, glob-search, get-source) and compiler information (diagnostics, type errors, warnings, hover types). Its index follows the session's cwd — if the work lives in a linked worktree, `cd` there first, or metals will silently return nothing
- context7 mcp for up to date documentation on third party code
- kapture mcp to observe browser tabs

**First, evaluate which skills apply to the task.** Before any planning, exploration, or code, go through the available skills list and read the ones relevant to the technologies and concerns in scope, so their conventions shape the work from the start rather than being retrofitted in review.
Test code counts. Fixtures, helpers, and assertions live in the repo, get read more often than written, and set the example for the next test — apply the same skill survey and idiom discipline you would for production code.
**Grill the plan before you write any of it.** Walk down each branch of the design tree and interview me with `AskUserQuestion`, one question per call, waiting for the answer before asking the next — four questions in one call get four shallow answers. Carry your own recommended answer with every question. If the codebase can settle a question, read the codebase instead of asking it. Name any term that is fuzzy or overloaded and propose the precise one in its place. When I state how something works, check that the code agrees, and surface the contradiction when it doesn't. Stop when the open branches are resolved, not when the questions run out.

For any non-trivial change, apply these pre-flight habits:
- **reuse-first** — survey existing extensions, helpers, transitive deps, and combinators (metals get-usages/glob-search, ripgrep) before adding new code, including test idioms
- **fail-loud** — surface failures via Option/Either/Validated/exceptions; no silent fallbacks or sentinel values
- **factor-out** — scan your diff for repeated constants, fixtures, or 3+-line blocks; extract on the second occurrence, not the third

Before opening any PR, run the **refactor-completeness** audit:
- diff-audit against master (catch accidental reverts from a stale branch base)
- schema/config parity (numeric bounds, codec validators, config files, test assertions)
- new components have at least a smoke test
- README/comments/docstrings still match the code

Before each commit (and again before opening a PR), run the **compliance-gate** skill:
- detects applicable skills from the diff and reviews against each applicable skill's body, catching judgment-shaped violations that a pattern check can't express

Read the CLAUDE.md root file before you do anything.
Clarify any doubt with `AskUserQuestion` rather than guessing at it.
#$ARGUMENTS
