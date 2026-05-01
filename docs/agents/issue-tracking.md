# Issue Tracking

This project tracks work with **knot** — markdown tickets under `.tickets/` (closed tickets auto-archive to `.tickets/archive/`). Config lives in `.knot.edn` at the repo root.

## Use the `knot` skill

For any ticket-shaped intent — "what's next", "track this", "show me <id>", "I'm done", "blocked on X", "any open bugs?" — invoke the `knot` skill. The CLI keeps frontmatter, the dep graph, and the archive consistent, and resolves partial IDs across live + archive.

## Never hand-edit `.tickets/`

No `Read`, `cat`, `grep`, `Write`, `Edit`, `sed`, or `mv` against files under `.tickets/`. Use `knot show` / `knot list --json` / `knot create` / `knot add-note` / `knot close`.

If a `knot` command behaves unexpectedly, surface the bug — don't bypass.

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

`GH-`, `ENG-`, `LIN-`, `JIRA-` point at *other* trackers (GitHub Issues, Linear, Jira). Use the matching tool for those, not `knot`.
