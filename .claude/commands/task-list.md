List tasks in `tasks/active/` and `tasks/backlog/`, scoped to the current project.

0. Find the spine root: `dirname $(dirname $(readlink -f .claude/commands))`. Use this path as the base for all task directory lookups below.
1. Determine the current project prefix:
   - Read `<spine-root>/projects/projects.md` to get the prefix→directory table.
   - Compare the current working directory against `<spine-root>/projects/<directory>`. If there's a match, note the corresponding prefix (e.g. `exo`).
   - If the cwd is the spine root itself (or no project match), treat prefix as `*` (show all tasks).
2. List files in both `<spine-root>/tasks/active/` and `<spine-root>/tasks/backlog/` using `find <dir> -maxdepth 1 -name "*.md"` (never bare globs — they error in zsh when no files match).
3. Filter the file list: keep only files whose name starts with `<prefix>-` or `x-` (cross-cutting). If prefix is `*`, keep all files.
4. For each matching file, read its title (first `#` heading), its `Status:` line, and its `**Type:**` line if present.
5. Mark every task whose `**Type:**` is `Bug` with a `[bug]` tag in the listing. If the user asked for bugs specifically, list only those.
6. Present as two sections — **Active** and **Backlog** — with the filename, title, and status for each entry. If a directory has no matching tasks, note it as such.
7. At the top, note which project scope is active (e.g. "Scoped to: abc (my-project)" or "Showing all projects").
