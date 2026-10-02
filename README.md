# Spine

Spine is a meta-repository that orchestrates context across multiple independent projects. It does not contain application code — it holds planning state, cross-cutting guidelines, and shared Claude Code tooling that apply to all projects in the workspace.

Each project lives under `projects/` as its own independent git repository. Spine tracks the work, not the code.

---

## Repository layout

```
projects/           # Independent git repos (gitignored)
  projects.md       # Registry of project directories and their three-letter prefixes
tasks/              # Planning files (gitignored)
  TEMPLATE.md       # Blank task scaffold
  active/           # In-progress tasks
  backlog/          # Queued tasks
  completed/        # Archived tasks
.claude/
  commands/         # Custom slash commands available in Claude Code
  skills/           # Shared skill packages loaded by Claude on demand
```

`projects/` and `tasks/` are gitignored. Only the scaffolding and tooling are tracked.

---

## Adding a project

1. Clone or create the project repository under `projects/<name>/`.
2. Open Claude Code from the Spine root and run `/projects-update`.
   Claude will detect the new directory, ask you to confirm a three-letter prefix, update `projects/projects.md`, and create symlinks so the project inherits Spine's shared commands and skills.

The symlinks created are:

```
projects/<name>/.claude/commands  →  ../../../.claude/commands
projects/<name>/.claude/skills    →  ../../../.claude/skills
```

Any Claude Code session opened inside a subproject automatically has access to the same slash commands and skill packages as the Spine root.

---

## Working with tasks

Tasks are markdown files that capture planning context so it survives across sessions.

### Creating a task

```
/task-new
```

Claude asks for a name, scope prefix, context, and key files, then creates a file in `tasks/active/` from `tasks/TEMPLATE.md`.

Filename convention: `<prefix>-<kebab-case-name>.md`

| Prefix | Scope |
|--------|-------|
| `abc-` | Single project — use the three-letter identifier from `projects/projects.md` |
| `x-`   | Cross-cutting — touches two or more projects |

### Listing tasks

```
/task-list
```

Prints all files in `tasks/active/` and `tasks/backlog/` with their title and status.

### Loading a task

```
/task-load <partial filename>
```

Reads the task file, loads the relevant project `CLAUDE.md` files, and summarises the current phase and next unchecked items. Use this at the start of any session that continues existing work.

### Archiving a task

```
/task-done <partial filename>
```

Sets `Status: DONE` and moves the file to `tasks/completed/`.

### Task file structure

```markdown
# Task: Name

## Status: PLANNING | IN PROGRESS | BLOCKED | DONE

## Scope
<!-- Prefix and repos touched -->

## Context
<!-- Why, background, constraints, deadline -->

## Key Decisions
- We chose X because Y

## Important Files
- projects/...

## Phases

### Phase 1: …
- [ ] item
```

---

## Recommended workflow

```
1. Plan      — discuss the problem with Claude, explore codebases, surface edge cases
2. Capture   — /task-new  →  fill in context, decisions, phases
3. Execute   — fresh session, /task-load <name>, then work (or spawn parallel agents)
4. Archive   — /task-done <name>
```

Opening a fresh session for execution (step 3) keeps the context window clean and prevents planning noise from contaminating implementation.

---

## Slash commands reference

| Command | What it does |
|---------|-------------|
| `/go [args]` | Orient and start a session with full MCP tooling active |
| `/task-new` | Create a new task file from the template |
| `/task-list` | List active and backlog tasks |
| `/task-load <name>` | Load a task and orient for a work session |
| `/task-done <name>` | Mark a task done and archive it |
| `/projects-update` | Sync the project registry and create `.claude` symlinks in all subprojects |

---

## Shared skills

`.claude/skills/` contains skill packages that Claude loads on demand. They are available in every subproject via the symlinks created by `/projects-update`, and carry the cross-cutting conventions that apply across all projects.

### Attribution

The Scala type-design material in the `types-first` skill (the "make illegal states unrepresentable" / "parse, don't validate" discipline and the type-feature catalogue) is adapted, in this repository's house style, from [`jpablo/vibe-types`](https://github.com/jpablo/vibe-types) (Apache-2.0). The skills here are maintained as our own and evolve independently; the upstream project is the original source of the catalogue's structure and many of its examples.
