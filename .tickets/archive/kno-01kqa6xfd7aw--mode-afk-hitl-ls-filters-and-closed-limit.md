---
id: kno-01kqa6xfd7aw
title: Mode (afk/hitl), ls filters, and closed --limit
status: closed
type: task
priority: 2
mode: afk
created: '2026-04-28T14:12:00.551317295Z'
updated: '2026-04-28T14:54:29.209558565Z'
closed: '2026-04-28T14:54:29.209558565Z'
parent: kno-01kqa804gmgx
external_refs:
- docs/prd/knot-v0.md
- issues/0009-mode-filters-closed-limit.md
deps:
- kno-01kqa6xf7mde
- kno-01kqa6xf8rcr
---
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

## Notes

**2026-04-28T14:54:29.209558565Z**

Implemented via strict RED→GREEN TDD (108 tests / 904 assertions, all green).

Highlights:
- New knot.query/filter-tickets primitive — set-valued criteria (:status :assignee :tag :type :mode), AND across keys, OR within a key (overlap for :tag). Shared by ls and ready.
- ls semantics: explicit --status overrides the default non-terminal filter, so 'ls --status closed' surfaces archived tickets without needing a separate flag.
- ready: filters apply BEFORE --limit truncation. Locked in by a 3-afk + 3-hitl '--mode afk --limit 2' test that would fail under naive 'limit then filter' code.
- closed-cmd sorts by :closed desc; tickets missing :closed sort last via a stable comparator. --limit takes the newest; --json supported.
- --afk / --hitl sugar in main.clj resolves to :mode in create-handler. Explicit --mode wins over the shortcut; --afk + --hitl together throws ex-info ('mutually exclusive') rather than picking silently.
- Body freeform: no enforcement code exists or was added — parse/render is a verbatim subs round-trip. Two regression tests in cli_test (freeform-body-round-trip-test) guard against future code stripping or rewriting freeform sections like '## Reproduction Steps'.

Wiring: list-handler reused for ready/blocked/closed (all share --json/--limit/filter shape). ls-handler keeps its own spec because it owns the special --status-overrides-default semantic via cli/ls-cmd. New 'closed' route added to main's case dispatch and usage text.
