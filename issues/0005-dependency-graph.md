---
id: issue-0005
title: Dependency graph - dep, undep, dep tree, dep cycle, ready, blocked
status: ready
type: afk
blocked_by:
  - issue-0003
  - issue-0004
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0005-dependency-graph.md
---

# Dependency graph - dep, undep, dep tree, dep cycle, ready, blocked

## Parent document

`docs/prd/knot-v0.md`

## What to build

The dependency-graph layer in `knot.query` plus the commands that consume it: `dep`/`undep` to add/remove edges, `dep tree <id>` to render an ASCII tree, `dep cycle` for project-wide DFS scanning, and the `ready`/`blocked` query commands. Cycle detection runs on every `dep` add and rejects with the offending path. Broken references (`:deps` or `:parent` pointing to a missing ticket) render with a `[missing]` marker plus a stderr warning, never abort.

## User stories covered

- 9 (`knot ready` lists open/in-progress tickets whose dependencies are all closed)
- 10 (`knot blocked` lists tickets whose dependencies are open)
- 11 (`knot dep tree <id>` renders ASCII dependency tree)
- 12 (`knot dep tree <id> --full` shows duplicate branches in full)
- 13 (adding a dependency fails if it would create a cycle)
- 14 (`knot dep cycle` scans for any pre-existing cycles in open tickets)
- 24 (broken `:parent`/`:deps` references render with `[missing]` and stderr warning, do not abort)

## Acceptance criteria

- [ ] `knot dep <from> <to>` adds `to` to the `:deps` array of `from`
- [ ] `knot undep <from> <to>` removes `to` from `from`'s `:deps`
- [ ] Cycle detection runs on every `dep` add (DFS from the new dep target looking for the source); rejects with the offending path printed to stderr; exit 1
- [ ] `knot dep tree <id>` renders an ASCII tree using box-drawing characters; dedupes already-seen branches with `↑` markers by default
- [ ] `knot dep tree <id> --full` shows duplicate branches in full (parity with the deduped form)
- [ ] `knot dep tree --json` emits a nested map (no envelope wrapping)
- [ ] `knot dep cycle` performs project-wide DFS over open tickets, prints any cycles found to stderr, exits 1 if any cycle found else 0
- [ ] `knot ready` lists open or in-progress tickets whose `:deps` are all in terminal status; sorted by priority asc then `:created` desc
- [ ] `knot blocked` lists tickets with at least one non-terminal `:deps` entry
- [ ] Both `ready` and `blocked` support `--json`
- [ ] `knot show <id>` does NOT yet render computed inverse sections (deferred to slice 6) but does display the raw `:deps`/`:parent` fields
- [ ] Broken `:deps` or `:parent` references (pointing to a missing ticket) render with `[missing]` marker in human output; emit a stderr warning; never abort
- [ ] `knot.query` is a pure namespace over a sequence of tickets — no I/O — to keep it Pathom3-feedable
- [ ] Tests: cycle detection (positive: self-loops, multi-cycles; negative: legal DAGs), `ready`/`blocked` partitioning under various dep states, `dep tree` dedup with `↑` markers and `--full` parity, broken-reference warning emission

## Blocked by

- issue-0003 (`issues/0003-lifecycle-transitions-and-archive.md`)
- issue-0004 (`issues/0004-config-and-init.md`)
