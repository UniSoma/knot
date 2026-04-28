---
id: issue-0003
title: Lifecycle transitions and archive auto-move
status: done
type: afk
blocked_by:
  - issue-0001
parent: docs/prd/knot-v0.md
created: 2026-04-28
completed: 2026-04-28
filename: issues/0003-lifecycle-transitions-and-archive.md
---

# Lifecycle transitions and archive auto-move

## Parent document

`docs/prd/knot-v0.md`

## What to build

The status-transition machinery: `status`, `start`, `close`, `reopen` commands; centralized `:updated` and `:closed` timestamping in `knot.store/save!`; auto-archive on terminal-status transitions and auto-restore on reopen; file-location self-healing when hand-edits desync status and directory; and full-history loading (live + archive) so subsequent slices can query across both transparently.

## User stories covered

- 33 (`start`, `close`, `reopen` shortcuts for the three most common transitions)
- 34 (`:updated` bumped on every Knot-mediated change)
- 35 (`:closed` set on terminal transitions, cleared on transitions out)
- 45 (closed tickets auto-move to `.tickets/archive/`)
- 46 (reopening an archived ticket auto-restores its file)

## Acceptance criteria

- [x] `knot status <id> <new-status>` transitions a ticket to any configured status (any-to-any allowed in v0)
- [x] `knot start <id>` is sugar for transitioning to `in_progress`
- [x] `knot close <id>` is sugar for transitioning to the first terminal status
- [x] `knot reopen <id>` transitions a closed ticket back to `open`
- [x] `knot.store/save!` stamps `:updated` on every persisted change (centralized; no scattered call sites)
- [x] `knot.store/save!` sets `:closed` when the new status is in `:terminal-statuses` and the prior status was not (or was a different terminal); clears `:closed` when the new status is non-terminal
- [x] On every save, the store reconciles the file's directory with whether the ticket's status is terminal — terminal → `<tickets-dir>/archive/`, non-terminal → `<tickets-dir>/`
- [x] Slug suffix preserved across archive moves
- [x] `knot.store` load-all walks both `<tickets-dir>/*.md` and `<tickets-dir>/archive/*.md` so all subsequent reads span the full history
- [x] File-location self-heals: a hand-edit that places a terminal-status ticket in the live directory (or vice versa) is corrected on the next Knot-mediated save
- [x] Tests: terminal/non-terminal `:closed` set/clear, `:updated` always bumped, archive auto-move and auto-restore, slug preservation across moves, location self-healing

## Blocked by

- issue-0001 (`issues/0001-foundation-create-and-show.md`)

## Implementation notes

### `save!` contract change

`save!` now takes a sixth `opts` argument: `{:now <iso-string?> :terminal-statuses <set?>}`. The opts are required for the centralization to work — `:now` becomes the `:updated` and `:closed` stamp; `:terminal-statuses` decides archive routing. When `:now` is absent, `save!` falls back to a wall-clock `Instant`. When `:terminal-statuses` is absent, no status is treated as terminal (everything stays in the live directory). Callers (currently just `cli/create-cmd` and the lifecycle commands) pass these from the resolved context.

The `slug` argument is now nullable. When `nil`, `save!` recovers the slug from the existing on-disk filename via a `<id>--<slug>.md` regex. This is what keeps slugs stable across status transitions without the lifecycle commands having to track them — `status-cmd`/`start-cmd`/`close-cmd`/`reopen-cmd` all pass `nil` for `slug`. `create-cmd` still passes the freshly-derived slug because there is no existing file to recover from.

### `:closed` stamping rules

`stamp-timestamps` (private helper) implements the three-way logic:

- **Crossing into a terminal status (or switching between two different terminals):** set `:closed` to `now`.
- **Already in the same terminal status (e.g. body-only edit while still closed):** preserve the existing `:closed` value carried in the input frontmatter. The store does not re-load to fetch it; the load → modify → save discipline used by the lifecycle commands keeps the value in the in-memory ticket.
- **New status is non-terminal:** `dissoc :closed` entirely. Reopen leaves no residual `:closed` key.

Prior status is determined by reading the existing on-disk file (in either live or archive). For new tickets there is no prior status, so the first save into a terminal status correctly stamps `:closed`.

### File-location self-healing

`save!` always looks up the existing path via `find-existing-path` (which checks both live and archive). If the existing path differs from the computed target path, the existing file is deleted after the new file is written. This makes self-healing fall out of the same code that performs deliberate archive moves — there is no separate "heal" code path. It also handles the slug-renamed-on-disk case: a stale file at a different slug is removed when `save!` writes to the new slug.

The archive subdirectory name is fixed (`"archive"`) per the PRD; no config knob.

### `load-all` ordering

Files are sorted by *filename* (not full path). This keeps live and archive entries interleaved by id, which matches how `ls` and future `closed --limit N` queries want to traverse them. The previous sort-by-full-path would have grouped all archive entries after all live entries, which would skew any priority/created-based downstream sorts.

### `close` and the "first terminal status"

`close-cmd` resolves its target by walking `:statuses` (the configured ordered list) and picking the first entry that is also in `:terminal-statuses`. With the v0 defaults (`:statuses ["open" "in_progress" "closed"]`, `:terminal-statuses #{"closed"}`) this yields `"closed"`. With a richer config like `:statuses ["open" "review" "done" "wontfix"]` and `:terminal-statuses #{"done" "wontfix"}`, `close` picks `"done"` — the intuitive default. There is a `"closed"` literal fallback when neither key resolves a terminal status, but with the standard defaults that branch is dead.

### `reopen` always goes to `"open"`

Per the AC, `reopen` is sugar for transitioning to literal `"open"` — not "the first non-terminal status from `:statuses`". This keeps the command predictable across configs that may rename the active status.

### Tests not added (deferred to later slices)

- Symmetric link maintenance across archive moves (issue-0006).
- `--summary` flag on `close` for one-shot close-with-note (issue-0007).
- Broken-reference warnings on load (issue-0005).
