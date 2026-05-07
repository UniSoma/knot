---
id: kno-01kr03swmer9
title: Surface AC progress in list tables and prime
status: in_progress
type: task
priority: 2
mode: afk
created: '2026-05-07T02:20:54.791397129Z'
updated: '2026-05-07T21:45:22.601030268Z'
tags:
- v0.3
- acceptance
acceptance:
- title: ls-columns gains an AC column inserted immediately before :title.
  done: true
- title: Cell renders as "d/t" via acceptance/progress for tickets with AC; renders as "-" for tickets without AC.
  done: true
- title: ls-table omits the AC column header and the column entirely when no ticket in the input has (seq :acceptance).
  done: true
- title: Conditional column propagates to ls, ready, blocked, closed views.
  done: true
- title: Closed view shows the AC column; force-closed tickets render as e.g. 2/5 (audit signal).
  done: true
- title: prime-in-progress-line and prime-ready-line render an AC slot before title when any ticket in the section has AC; otherwise the slot is omitted.
  done: true
- title: knot.query/ready-to-close? [ticket active-status] returns (and (= status active-status) (seq ac) (acceptance/complete? ac)).
  done: true
- title: prime-cmd data assembly partitions active-status tickets so a ticket appears in either :ready-to-close or :in-progress, never both.
  done: true
- title: 'prime-text renders ## Ready to close between ## In Progress and ## Ready.'
  done: true
- title: Ready to close section uses the in-progress line shape (id, type, mode, pri, age, ac, title — 7 cols when AC slot is present; 6 cols when omitted).
  done: true
- title: Ready to close section is sorted by :updated descending.
  done: true
- title: Ready to close section is uncapped (no "... +N more" footer).
  done: true
- title: Ready to close section is omitted entirely when no tickets match the predicate.
  done: true
- title: 'Hitl nudge for Ready to close: "All acceptance criteria are checked — close with knot close <id> --summary ...".'
  done: true
- title: 'Afk nudge for Ready to close: "Close these before grabbing new tickets."'
  done: true
- title: prime-json gains a ready_to_close array parallel to in_progress, ready, and recently_closed, with the same per-ticket projection as in_progress.
  done: true
- title: No acceptance_progress derived field is added to per-ticket JSON projections.
  done: true
- title: ls --json output shape is unchanged (raw acceptance pass-through).
  done: true
- title: 'Tests cover: column conditional on/off based on result set; empty cell renders as "-"; column position immediately before TITLE.'
  done: true
- title: 'Tests cover: prime line shape with and without the AC slot; partition correctness (active + all AC done → ready-to-close, not in-progress).'
  done: true
- title: 'Tests cover: section ordering in prime-text; omit-when-empty for Ready to close; sort order; ready_to_close JSON shape parity with text section.'
  done: true
- title: .claude/skills/knot/SKILL.md updated with the new column, prime section, and JSON shape in the same commit.
  done: true
- title: 'CHANGELOG entry under [Unreleased]/Added: AC column, AC progress in prime lines, Ready to close section, ready_to_close JSON array.'
  done: true
- title: README listing/prime sections mention AC visibility.
  done: true
- title: bb test passes; existing snapshot tests for prime-text, prime-json, and ls-table updated; clj-kondo --lint src test baseline preserved.
  done: true
deps:
- kno-01kr03rmk9p9
---

## Description

AC progress is invisible at the planning level. Adds a conditional `AC` column to the shared list table (propagates to `ls`/`ready`/`blocked`/`closed`) and AC awareness to prime. New `## Ready to close` prime section pairs with the close-gate to surface tickets where all AC are checked but status is still active — the natural call-to-action prompt.

## Design

### Listing tables

Shared `ls-columns` gains an `AC` column rendered as `2/5`, positioned right before `TITLE`, conditional on at least one ticket in the result set having `(seq :acceptance)`. Tickets without AC render as `-`. Header is `AC`. Propagates to `ls`, `ready`, `blocked`, `closed`. No AC-specific colors in v1.

### Prime lines

Same conditional pattern in `prime-in-progress-line` and `prime-ready-line` — insert AC slot before title, omit if no ticket in the section has AC. Whitespace-only, no ANSI.

### Ready-to-close section

New `knot.query/ready-to-close?` predicate: `(and (= status active-status) (seq ac) (acceptance/complete? ac))`. `prime-cmd` data assembly partitions active-status tickets into `:ready-to-close` and `:in-progress` — mutually exclusive.

`prime-text` renders `## Ready to close` between `## In Progress` and `## Ready`. Uses the in-progress line shape (with age column). Sorted by `:updated` descending. Uncapped. Omitted when empty (unlike `## Ready` which always renders so its directive is visible).

Heading nudges:
- hitl: `All acceptance criteria are checked — close with \`knot close <id> --summary "..."\`.`
- afk: `Close these before grabbing new tickets.`

### JSON

`prime-json` adds a `ready_to_close` array parallel to `in_progress` / `ready` / `recently_closed`, using the same per-ticket projection as `in_progress`. No derived `acceptance_progress` field on individual tickets — raw `:acceptance` is already in the JSON. `ls --json` is unchanged.

### Out of scope

- New `knot stats` command — already tracked separately as kno-01kqxgt2jkf2; the `acceptance/progress` and `acceptance/complete?` primitives from Ticket A are reusable there.
- AC-specific colors in `ls-table` — leave to kno-01kqdaxz86nv (richer per-value colors).
- AC visibility in `knot show` — already shows AC inline.
- AC visibility in `knot status` single-ticket text — not needed.
