---
id: issue-0006
title: Symmetric links and computed inverse sections in show
status: ready
type: afk
blocked_by:
  - issue-0005
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0006-symmetric-links-and-inverse-sections.md
---

# Symmetric links and computed inverse sections in show

## Parent document

`docs/prd/knot-v0.md`

## What to build

`knot link` and `knot unlink` with symmetric maintenance — a link from A to B is also written into B's `:links` — and the computed inverse sections in `knot show <id>`: Blockers, Blocking, Children, Linked. These sections are computed at render time from the in-memory ticket set, never persisted. Once this lands, story 3 (full graph context on `show`) is satisfied.

## User stories covered

- 3 (`knot show <id>` renders frontmatter, body, and computed inverse relationships)
- 15 (`knot link A B C` creates symmetric links across multiple tickets in one command)
- 16 (links are maintained symmetrically — write to both ticket files)

## Acceptance criteria

- [ ] `knot link A B [C ...]` accepts two or more ticket IDs and creates symmetric links across all pairs in one command
- [ ] `knot link` writes to all referenced ticket files, idempotent on each side (re-linking is a no-op)
- [ ] `knot unlink A B` removes the symmetric link from both files; idempotent
- [ ] Links survive archive transitions because `:links` references IDs not paths — verified by an integration test that links a ticket, closes it (auto-archive), then reads the link from the still-live counterpart
- [ ] `knot show <id>` renders four computed sections after the body: `## Blockers` (this ticket's `:deps`), `## Blocking` (tickets that have this ticket in their `:deps`), `## Children` (tickets whose `:parent` is this ticket), `## Linked` (this ticket's `:links`)
- [ ] Each computed section omits if empty
- [ ] Broken references in any computed section render with `[missing]` marker (consistent with slice 5)
- [ ] `--json` output for `show` includes the computed inverse fields under snake_case keys (`blockers`, `blocking`, `children`, `linked`)
- [ ] Tests: symmetric write across both files, idempotent link/unlink, computed inverse sections in human and JSON output, links surviving archive transitions

## Blocked by

- issue-0005 (`issues/0005-dependency-graph.md`)
