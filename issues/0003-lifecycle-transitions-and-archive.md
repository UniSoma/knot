---
id: issue-0003
title: Lifecycle transitions and archive auto-move
status: ready
type: afk
blocked_by:
  - issue-0001
parent: docs/prd/knot-v0.md
created: 2026-04-28
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

- [ ] `knot status <id> <new-status>` transitions a ticket to any configured status (any-to-any allowed in v0)
- [ ] `knot start <id>` is sugar for transitioning to `in_progress`
- [ ] `knot close <id>` is sugar for transitioning to the first terminal status
- [ ] `knot reopen <id>` transitions a closed ticket back to `open`
- [ ] `knot.store/save!` stamps `:updated` on every persisted change (centralized; no scattered call sites)
- [ ] `knot.store/save!` sets `:closed` when the new status is in `:terminal-statuses` and the prior status was not (or was a different terminal); clears `:closed` when the new status is non-terminal
- [ ] On every save, the store reconciles the file's directory with whether the ticket's status is terminal — terminal → `<tickets-dir>/archive/`, non-terminal → `<tickets-dir>/`
- [ ] Slug suffix preserved across archive moves
- [ ] `knot.store` load-all walks both `<tickets-dir>/*.md` and `<tickets-dir>/archive/*.md` so all subsequent reads span the full history
- [ ] File-location self-heals: a hand-edit that places a terminal-status ticket in the live directory (or vice versa) is corrected on the next Knot-mediated save
- [ ] Tests: terminal/non-terminal `:closed` set/clear, `:updated` always bumped, archive auto-move and auto-restore, slug preservation across moves, location self-healing

## Blocked by

- issue-0001 (`issues/0001-foundation-create-and-show.md`)
