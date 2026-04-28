# knot

A babashka CLI ticket tracker for solo developers. Tickets are markdown
files with YAML frontmatter under `.tickets/`; closed tickets auto-move
to `.tickets/archive/`. Built for one human on one machine, with
first-class support for handing autonomous work to an AI agent.

See [`docs/prd/knot-v0.md`](docs/prd/knot-v0.md) for the full design
rationale and the v0 acceptance criteria.

## Why Knot?

Knot is for solo developers who want tickets to live with the code, not
in a hosted issue tracker or a hidden database. Each ticket is a markdown
file you can edit, grep, diff, review, and commit with the work it
describes.

It keeps the useful properties of a plain-file tracker — readable files,
git-native history, no server — and adds the pieces that matter for a
Clojure and AI-agent workflow: built-in JSON output, configurable project
schema, sortable collision-resistant IDs, dependency queries, and a
first-class `afk`/`hitl` mode for deciding what an agent can run alone.

If you need multi-user coordination, permissions, dashboards, or hosted
notifications, use an issue tracker. If you need a fast local backlog that
an editor, shell, git, and agent can all understand, use Knot.

## Install

Knot is distributed as a bbin-compatible babashka script:

```sh
bbin install https://github.com/UniSoma/knot --as knot
```

That's it. bbin reads `:bbin/bin` from `bb.edn`, drops a babashka shim
on PATH, and resolves runtime deps lazily on first run. To install from
a working copy during development:

```sh
bbin install . --as knot
```

To uninstall:

```sh
bbin uninstall knot
```

`knot` requires babashka 1.3.0 or later. Runtime dependencies ship
bundled with bb, so no `:deps` resolution happens at install time.

## Quick tour

```sh
# Set up a project (writes .knot.edn stub + creates .tickets/)
knot init

# Create a ticket (defaults: type=task, priority=2, mode=hitl)
knot create "Fix login redirect"
knot create "Backfill telemetry" -d "Description body" --afk -p 1

# List the live (non-terminal) backlog
knot ls
knot ls --json                 # snake_case JSON for shell pipelines

# Inspect one ticket — frontmatter, body, computed graph context
knot show <id>                 # partial ids work: 01jq8p4a → unique full id

# Move work through the workflow
knot start  <id>               # → in_progress, bumps :updated
knot close  <id> --summary "shipped in #482"  # → closed + appended note
knot reopen <id>               # restores from archive

# Find what to pick up
knot ready                     # non-terminal + non-blocked, sorted by priority
knot ready --mode afk          # only agent-runnable
knot blocked                   # tickets with at least one open dep

# Relationships
knot dep <from> <to>           # cycle-checked dep add
knot link a b c                # symmetric :links across every pair
knot dep tree <id>             # ASCII tree, --full to expand dups

# Annotation
knot add-note <id> "raced GC under load"
knot edit <id>                 # opens $VISUAL/$EDITOR
```

Every read command (`show`, `ls`, `ready`, `blocked`, `closed`,
`dep tree`, `prime`) accepts `--json` and emits snake_case keys. Stdout
carries data only; warnings and errors go to stderr.

## `.knot.edn` schema

`.knot.edn` at the project root is optional — defaults work zero-config.
`knot init` writes a self-documenting stub with every key inline-commented.

| Key                  | Default                              | Notes                                                   |
| -------------------- | ------------------------------------ | ------------------------------------------------------- |
| `:tickets-dir`       | `".tickets"`                         | Where ticket files live, relative to `.knot.edn`.       |
| `:prefix`            | auto-derived from project dir name   | Project shortcode prefixed onto every generated id.     |
| `:project-name`      | nil                                  | Human-readable name shown in `knot prime`.              |
| `:default-assignee`  | git `user.name`                      | Fallback when `--assignee` is omitted.                  |
| `:default-type`      | `"task"`                             | Must be a member of `:types`.                           |
| `:default-priority`  | `2`                                  | Integer 0..4 (0 = highest).                             |
| `:statuses`          | `["open" "in_progress" "closed"]`    | Workflow, ordered. Add e.g. `"review"` to extend.       |
| `:terminal-statuses` | `#{"closed"}`                        | Tickets in these states auto-move to archive.           |
| `:types`             | `["bug" "feature" "task" "epic" "chore"]` | Allowed values for ticket `:type`.                 |
| `:modes`             | `["afk" "hitl"]`                     | `afk` = agent-runnable; `hitl` = needs a human.         |
| `:default-mode`      | `"hitl"`                             | Must be a member of `:modes`.                           |

Project root is discovered by walking up from cwd looking for `.knot.edn`
or `.tickets/`. When `.knot.edn` is found, its `:tickets-dir` controls
where tickets live. Unknown keys warn and are dropped; invalid values fail
at command start with a clear message.

## AI-agent integration

`knot prime` emits a five-section markdown primer summarizing project
state — preamble, project metadata, in-progress tickets, ready tickets
(capped at 20 by default), and a schema/command cheatsheet — for
injection into a fresh AI agent session.

```sh
knot prime                    # five-section markdown primer
knot prime --mode afk         # filter ready section to agent-runnable work
knot prime --limit 5          # override the default ready cap of 20
knot prime --json             # bare object: {project, in_progress, ready, ready_truncated, ready_remaining}
```

`knot prime` always exits 0, including when run from a directory with
no Knot project (the preamble in that case directs the user to `knot
init`), when the project has zero tickets, or when only archived
tickets exist. This makes it safe to wire into a global session-start
hook.

### Session-start hook

Configure your agent's session-start hook to run `knot prime` from the
project. The hook should read stdout and inject it as session context;
no JSON wrapper is required because `knot prime` emits plain markdown.
`knot init` does not modify agent configuration; hook setup is opt-in,
never automatic.

For example, Claude Code users can add this to `~/.claude/settings.json`
(global) or `<project>/.claude/settings.json` (project-local):

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "startup",
        "hooks": [
          {
            "type": "command",
            "command": "knot prime"
          }
        ]
      }
    ]
  }
}
```

For agent-runnable session presets, run `knot prime --mode afk` to
surface only `mode: afk` ready tickets.

## Philosophy

Knot is shaped by a few load-bearing assumptions. If your situation
violates them, a different tool will serve you better.

- **File-based, no synthetic database.** Tickets are markdown files with
  YAML frontmatter. You can inspect, grep, diff, and commit them directly.
  For agent workflows, prefer `knot show` for reads and Knot commands for
  all writes so computed graph context and frontmatter stay consistent.
- **Git-native, opt-in commits.** Knot never runs `git add`, `git commit`,
  or `git push`. Your git log reflects your intent, and ticket changes
  bundle naturally with the code changes that motivate them.
- **No daemons, no servers, no index.** Every command does a full
  reparse of `.tickets/` (live + archive). At personal scale, the
  cost is negligible and the simplicity wins.
- **No file locking, last writer wins.** Knot is built for one human
  on one machine. There are no `.knot.lock` files, no fcntl, no MVCC.
  Two concurrent writers to the same ticket will produce the
  last-write-wins outcome you'd expect from a plain text file. If you
  need multi-writer coordination, this is the wrong tool.
- **Stateless IDs.** Ticket IDs are 12-char ULID suffixes
  (10 timestamp + 2 random) prefixed by a project shortcode. They sort
  chronologically, make collisions negligible at personal scale, and survive
  rebases, cherry-picks, and hand-edits without a registry. Layered
  prefix matching (`knot show 01jq8p4a`) keeps day-to-day typing
  short.
- **AI-agent-aware by design.** The `:mode` field (`afk` vs `hitl`)
  is a peer dimension to status and priority. `knot ready --mode afk`
  surfaces unblocked, agent-runnable work in one query, and
  `knot prime` keeps a fresh agent session oriented without any
  hand-written briefing.
- **Pure data layer.** `knot.ticket` and `knot.query` are pure;
  `knot.store` isolates filesystem I/O. Future query layers can build
  on these namespaces without touching the CLI.

## License

MIT. See [LICENSE](LICENSE).
