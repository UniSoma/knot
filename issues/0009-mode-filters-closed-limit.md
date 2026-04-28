---
id: issue-0009
title: Mode (afk/hitl), ls filters, and closed --limit
status: ready
type: afk
blocked_by:
  - issue-0004
  - issue-0005
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0009-mode-filters-closed-limit.md
---

# Mode (afk/hitl), ls filters, and closed --limit

## Parent document

`docs/prd/knot-v0.md`

## What to build

Plumb the `:mode` field (`afk` | `hitl`) through `create`, the schema, queries, and `ls`. Add filtering to `ls` for status, assignee, tag, type, and mode. Add `knot ready --mode afk` to surface unblocked, agent-runnable work. Add `knot closed --limit N` to query recently-closed tickets without parsing git log. Document that body sections beyond `## Notes` are freeform — no enforcement.

## User stories covered

- 5 (mark a ticket as `--afk` at create time)
- 6 (`knot ready --mode afk` returns unblocked, agent-runnable work)
- 25 (recently-closed tickets queryable via `knot closed --limit N`)
- 31 (body sections beyond `## Notes` are freeform — no enforcement)
- 32 (filtering by status, assignee, tag, type, and mode on `ls`)

## Acceptance criteria

- [ ] `knot create --mode <afk|hitl>` writes the canonical `:mode` to frontmatter
- [ ] `knot create --afk` and `--hitl` are sugar for `--mode afk`/`--mode hitl`
- [ ] Default mode comes from config `:default-mode` (default `"hitl"` per slice 4)
- [ ] `knot.query` exposes a single composable filter primitive used by both `ls` and `ready`
- [ ] `knot ls` flags: `--status`, `--assignee`, `--tag`, `--type`, `--mode` (composable; multiple flags AND together)
- [ ] `knot ready --mode afk` filters ready set to mode = afk before applying any limit
- [ ] `knot closed --limit N` returns the N most-recently closed tickets (sorted by `:closed` desc); supports `--json`
- [ ] `knot closed` without `--limit` returns all closed tickets
- [ ] No body-section enforcement: `knot edit` and round-trip preserve any body content; tests confirm freeform `## Reproduction Steps` and similar survive
- [ ] Tests: filter composition matrix on `ls`, `ready --mode afk` with filter applied before limit, `closed --limit` ordering, mode round-trip on create→show

## Blocked by

- issue-0004 (`issues/0004-config-and-init.md`)
- issue-0005 (`issues/0005-dependency-graph.md`)
