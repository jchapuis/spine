Load a task file from the spine and orient for a work session.

0. Find the spine root: `dirname $(dirname $(readlink -f .claude/commands))`. Use this path as the base for all task directory lookups below.
1. Read the task file — look in `<spine-root>/tasks/active/` for a filename matching $ARGUMENTS (exact or partial match). If ambiguous, use AskUserQuestion to clarify.

2. From the **Scope** section, identify which projects are involved. For each, read its `CLAUDE.md`.

3. Summarize to the user:
   - Task title and current status
   - Current phase and the next unchecked items
   - Key decisions already recorded
   - Important files to keep in mind

5. Ask the user how they'd like to proceed or what to tackle first.
