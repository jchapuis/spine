Update the project registry and prefix shorthands in `projects/projects.md`, and ensure each subproject has symlinks to the shared spine `.claude/skills` and `.claude/commands` folders.

1. Read `projects/projects.md` if it exists (create it if not).
2. List directories currently present under `projects/`.
3. For each directory not yet in `projects/projects.md`, propose a three-letter prefix and use AskUserQuestion to confirm it or suggest an alternative.
4. Update `projects/projects.md` with a table of all projects:

   | Prefix | Directory |
   |--------|-----------|
   | ...    | ...       |

5. For each project directory under `projects/`:
   a. Ensure the project has a `.claude/` directory (create if absent).
   b. For each of `skills` and `commands`:
      - If the path `projects/<dir>/.claude/<name>` does not exist or is not already a symlink pointing to the correct target, create (or replace) a relative symlink:
        `projects/<dir>/.claude/<name>` → `../../../.claude/<name>`
      - Use `ln -sfn ../../../.claude/<name> projects/<dir>/.claude/<name>` via Bash.
      - Report whether each symlink was created, already correct, or replaced.
   c. Share the abide rubric (per-edit rule enforcement — see `.claude/skills/compliance-gate/README.md`):
      - Ensure `projects/<dir>/.abide/` exists (create if absent) — a real dir so each project keeps its own `.abide/events.jsonl` runtime log.
      - Symlink only the rules file, so all projects share one rubric while logging locally:
        `ln -sfn ../../../.abide/rubric.json projects/<dir>/.abide/rubric.json`
      - Report created / already correct / replaced.

6. Confirm all changes to the user. Remind them that per-edit enforcement also needs a
   one-time, per-project `npx @coldtea/abide init --project` (installs the agent hooks) and a
   running local kev server — both documented in `.claude/skills/compliance-gate/README.md`.
