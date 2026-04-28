---
id: issue-0009
title: Mode (afk/hitl), ls filters, and closed --limit
status: done
type: afk
blocked_by:
  - issue-0004
  - issue-0005
parent: docs/prd/knot-v0.md
created: 2026-04-28
completed: 2026-04-28
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

- [x] `knot create --mode <afk|hitl>` writes the canonical `:mode` to frontmatter
- [x] `knot create --afk` and `--hitl` are sugar for `--mode afk`/`--mode hitl`
- [x] Default mode comes from config `:default-mode` (default `"hitl"` per slice 4)
- [x] `knot.query` exposes a single composable filter primitive used by both `ls` and `ready`
- [x] `knot ls` flags: `--status`, `--assignee`, `--tag`, `--type`, `--mode` (composable; multiple flags AND together)
- [x] `knot ready --mode afk` filters ready set to mode = afk before applying any limit
- [x] `knot closed --limit N` returns the N most-recently closed tickets (sorted by `:closed` desc); supports `--json`
- [x] `knot closed` without `--limit` returns all closed tickets
- [x] No body-section enforcement: `knot edit` and round-trip preserve any body content; tests confirm freeform `## Reproduction Steps` and similar survive
- [x] Tests: filter composition matrix on `ls`, `ready --mode afk` with filter applied before limit, `closed --limit` ordering, mode round-trip on create→show

## Blocked by

- issue-0004 (`issues/0004-config-and-init.md`)
- issue-0005 (`issues/0005-dependency-graph.md`)

## Implementation notes

- New `knot.query/filter-tickets` primitive — set-valued criteria (`:status`, `:assignee`, `:tag`, `:type`, `:mode`); AND across keys, OR within a key (set overlap for `:tag`). Shared by `ls` and `ready`.
- `ls` semantic: explicit `--status` overrides the default non-terminal filter, so `ls --status closed` surfaces archived tickets without needing a separate flag.
- `ready` applies filters BEFORE `--limit` truncation. Locked in by a 3-afk + 3-hitl `--mode afk --limit 2` test that would fail under naive "limit then filter" code.
- `closed-cmd` sorts by `:closed` desc; tickets missing `:closed` sort last via a stable comparator. `--limit` keeps the newest; `--json` supported.
- `--afk` / `--hitl` sugar resolves to `:mode` in `main/create-handler`. Explicit `--mode` wins over the shortcut; `--afk --hitl` together throws `ex-info` ("mutually exclusive") rather than picking silently.
- Body freeform: no enforcement code exists or was added — `parse`/`render` is a verbatim `subs` round-trip. Two regression tests in `cli_test/freeform-body-round-trip-test` guard against future code stripping or rewriting freeform sections like `## Reproduction Steps`.
- Wiring: `list-handler` is reused for `ready`/`blocked`/`closed` (all share the `--json`/`--limit`/filter shape). `ls-handler` keeps its own spec because it owns the `--status`-overrides-default semantic via `cli/ls-cmd`. New `closed` route added to `main`'s case dispatch and usage text.
