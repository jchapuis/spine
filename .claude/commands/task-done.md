Mark a task as done and move it to the archive.

0. Find the spine root: `dirname $(dirname $(readlink -f .claude/commands))`. Use this path as the base for all file operations below.
1. Locate the task file in `<spine-root>/tasks/active/` matching $ARGUMENTS (exact or partial filename match). If ambiguous, use AskUserQuestion.
2. Update the `Status:` line to `DONE`.
3. Move the file to the spine's `tasks/completed/`.
4. Confirm the archived path to the user.
