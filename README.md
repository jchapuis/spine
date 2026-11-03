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
  agents/           # Subagent types (review-worker, used by /ck)
  hooks/            # ck-gate.sh (blocks PR creation until /ck has reviewed the branch) and its tests
.abide/
  rubric.json       # Per-edit rules for abide; this file is linked into every project
  finetune/         # Training pipeline for the local model abide queries
```

`projects/` and `tasks/` are gitignored. Only the scaffolding and tooling are tracked.

---

## Adding a project

1. Clone or create the project repository under `projects/<name>/`.
2. Open Claude Code from the Spine root and run `/projects-update`.
   Claude will detect the new directory, ask you to confirm a three-letter prefix, update `projects/projects.md`, and create symlinks so the project inherits Spine's shared commands, skills, agents and abide rubric.

The symlinks created are:

```
projects/<name>/.claude/commands  →  ../../../.claude/commands
projects/<name>/.claude/skills    →  ../../../.claude/skills
projects/<name>/.claude/agents    →  ../../../.claude/agents
projects/<name>/.abide/rubric.json  →  ../../../.abide/rubric.json
```

Once linked, a Claude Code session opened inside a subproject has the same slash commands, skills and agents as the Spine root. Re-run `/projects-update` whenever this list grows, so that existing projects pick up the new links.

Hooks are the exception. They live in `settings.json`, which isn't linked, so the PR gate is registered in the user settings instead (see [Review and PR flow](#review-and-pr-flow)).

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
3. Execute   — fresh session, /task-load <name> or /go <ask>, then work (or spawn parallel agents)
4. Review    — /ck before the PR opens (enforced), then the PR and its guided review
5. Archive   — /task-done <name>
```

Opening a fresh session for execution (step 3) keeps the context window clean and prevents planning noise from contaminating implementation.

---

## Review and PR flow

Code is checked at three points, each cheaper than the next one:

| When | What | How |
|------|------|-----|
| Every edit | [abide](https://github.com/coldteadotai/abide), a per-edit rule checker, asks a local model the rules in `.abide/rubric.json` | Claude Code hook, once `abide-enable` has run in the project |
| Every commit | `compliance-gate` mechanical layer (ripgrep, Scalafix, scalafmt) | Git pre-commit hook, once `compliance-gate/bin/install-gate <project>` has run; `/go` also runs the skill inline |
| Before the PR | `/ck`: `/code-review` and the full `compliance-gate` in two `review-worker` agents | `ck-gate.sh` blocks `gh pr create` until `/ck` has run |

**The PR gate.**
- `/ck` writes the last commit it reviewed into a stamp file, `<git-dir>/ck-reviewed`, one per
  worktree.
- `.claude/hooks/ck-gate.sh` is a `PreToolUse` hook on Bash. It blocks any command that opens a PR
  (`gh pr create`, `gh api …/pulls` POSTs, the GraphQL `createPullRequest` call) unless the stamp is
  HEAD. When newer commits sit on top of the stamp, it asks for `/ck <stamp>..HEAD`, so only the
  new work gets reviewed. Its tests are in `.claude/hooks/ck-gate.test.sh`.
- It is registered in the user settings (`~/.claude-perso/settings.json`), so sessions in every
  project get it. On a machine without the spine, it does nothing.
- It only covers PRs that Claude opens. Before pushing follow-up commits to an open PR, run
  `/ck <stamp>..HEAD` yourself.

**Fix by default.** `/ck` fixes every confirmed finding whose fix is clear, commits the fixes as
one commit, and then writes the stamp. Findings it can't confirm are dropped, each with its
reason. Only real decisions are sent to you: reversing a recorded choice, a trade-off between
options, context the repo doesn't have, or a change that goes past the diff.

**Plannotator.** The plugin is enabled in the user settings, so it is in every session:
- **Plan review.** Leaving plan mode opens the plan in Plannotator's review UI. `/go` won't start
  coding until the plan is approved there.
- **Inbox.** Questions for when you're away from the chat. Each repo has its own project in the
  inbox, and every PR gets one thread, `PR #<n>`, under the repo's main checkout. That thread holds
  the guided review (a walkthrough of the diff in reading order) together with `/ck`'s open decisions, sent as one message. Answers come back
  into the session that asked, and the follow-up commit gets an incremental `/ck`.
- **Diagrams for large PRs.** At 20+ files or 600+ changed lines, the gate also wants a
  [PR Lens](https://prlens.dev) diagram of the diff, rendered after the last commit. It goes with
  the guided review as an image. It is never pushed to prlens.dev or attached to the PR, because
  the repos are private.

---

## Slash commands reference

| Command | What it does |
|---------|-------------|
| `/go [args]` | Start work: tooling, skill survey, grilling, Plannotator plan review, `/ck` before the PR |
| `/ck [target]` | Pre-PR review in two agents; fixes findings, writes the stamp the PR gate checks, sends decisions to the inbox |
| `/ai-assisted-review <branch>` | Review someone else's PR and post findings as inline comments, one at a time |
| `/task-new` | Create a new task file from the template |
| `/task-list` | List active and backlog tasks |
| `/task-load <name>` | Load a task and orient for a work session |
| `/task-done <name>` | Mark a task done and archive it |
| `/projects-update` | Sync the project registry and create `.claude` and `.abide` symlinks in all subprojects |

---

## Shared skills

`.claude/skills/` contains skill packages that Claude loads on demand. They are available in every subproject via the symlinks created by `/projects-update`, and carry the cross-cutting conventions that apply across all projects.

### Attribution

The Scala type-design material in the `types-first` skill (the "make illegal states unrepresentable" / "parse, don't validate" discipline and the type-feature catalogue) is adapted, in this repository's house style, from [`jpablo/vibe-types`](https://github.com/jpablo/vibe-types) (Apache-2.0). The skills here are maintained as our own and evolve independently; the upstream project is the original source of the catalogue's structure and many of its examples.
