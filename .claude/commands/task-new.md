Create a new task file in the spine `tasks/active/` from the template.

0. Find the spine root: `dirname $(dirname $(readlink -f .claude/commands))`. Use this path as the base for all file operations below.
1. Determine the task name and scope from $ARGUMENTS or current conversation context. If either is unclear or missing, use AskUserQuestion to gather:
   - **Task name** — short descriptive title (will become the filename)
   - **Scope prefix** — project id matching a directory under `projects/` with a three-letter prefix according to `projects/projects.md` or `x` for cross-cutting
   - **Context** — why we're doing this, background, constraints, deadline if any
   - **Key files** — known entry points or critical files (optional)

2. Generate the filename: `<prefix>-<kebab-case-name>.md` (e.g. `abc-add-user-auth.md`). Place it in `<spine-root>/tasks/active/`.

3. Create the file using `<spine-root>/tasks/TEMPLATE.md` as the template. Fill in:
   - Title from the task name
   - `Status: PLANNING`
   - Scope prefix and repos touched
   - Context section from gathered information
   - Important Files if provided
   - Leave Key Decisions and Phases as scaffolding for the user to complete (populate from context if clearly available)

4. Print the created file path and contents so the user can review.
