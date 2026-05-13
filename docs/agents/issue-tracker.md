# Issue tracker: knot

This project tracks work with **knot** — markdown tickets under `.tickets/` (closed tickets auto-archive to `.tickets/archive/`). Config lives in `.knot.edn` at the repo root. There is no GitHub Issues / Linear / Jira workflow for engineering work on this project.

## Use the `knot` skill

For any ticket-shaped intent — "what's next", "track this", "show me <id>", "I'm done", "blocked on X", "any open bugs?" — invoke the `knot` skill. The CLI keeps frontmatter, the dep graph, and the archive consistent, and resolves partial IDs across live + archive.

## Never hand-edit `.tickets/`

No `Read`, `cat`, `grep`, `Write`, `Edit`, `sed`, or `mv` against files under `.tickets/`. Use `knot show` / `knot list --json` / `knot create` / `knot add-note` / `knot close`. If a `knot` command behaves unexpectedly, surface the bug — don't bypass.

## Conventions

- **Create an issue**: `knot create --type <bug|feature|task|chore|epic> --title "..." --description "..." --priority <1-4> --mode <hitl|afk> --tags tag1,tag2`. Prefer the `knot:create` skill for richer framing (auto-detected links/deps).
- **Read an issue**: `knot show <id>` (resolves partial IDs across live + archive).
- **List issues**: `knot list` with `--type`, `--tag`, `--mode`, `--status`, `--assignee`, `--priority`, `--limit`, `--json` filters.
- **Comment on an issue**: `knot add-note <id> "..."`.
- **Apply / remove labels (tags)**: `knot update <id> --tags <comma-list>` (replaces the full set).
- **Close**: `knot close <id> --summary "..."`. Won't-do is closed with `--summary "Won't do: ..."`.

## When a skill says "publish to the issue tracker"

Run `knot create` (or invoke the `knot:create` skill for guided framing).

## When a skill says "fetch the relevant ticket"

Run `knot show <id>`.

## Ticket types

`:types ["bug" "feature" "task" "epic" "chore"]` is an allow-list — knot has no opinion on semantics. Convention in this project:

| Type      | Meaning                                                    |
|-----------|------------------------------------------------------------|
| `bug`     | Defect in existing behavior — something is broken or wrong |
| `feature` | New user-invoked command or capability                     |
| `task`    | Internal/protocol/coordination work — no new user surface  |
| `chore`   | Cleanup, removal, audit, docs — refines existing surface   |
| `epic`    | Multi-ticket parent (unused so far)                        |

The fuzzy boundary is `feature` vs `task`. Discriminator: **does an end-user gain a new thing they can directly invoke or query?** If yes → feature; if no → task. Adding a new command is a feature; changing the JSON output shape of an existing command is a task.

## Hosted-tracker prefixes

`GH-`, `ENG-`, `LIN-`, `JIRA-` IDs point at *other* trackers (GitHub Issues, Linear, Jira). Use the matching tool for those, not `knot`.
