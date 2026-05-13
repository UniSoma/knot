---
id: kno-01kretd4pqtn
title: 'knot.el: sort transient + view-specific default orderings for list view'
status: closed
type: feature
priority: 3
mode: hitl
created: '2026-05-12T19:25:16.375278085Z'
updated: '2026-05-13T01:23:28.297010240Z'
closed: '2026-05-13T01:23:28.297010240Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: A sort transient (e.g. 'o' for order) exposes single-key sort by id, title, priority, status, type, mode, created, updated
  done: true
- title: Sort key + direction are buffer-local in knot-list-mode and persist across filter changes, manual 'g' refresh, and view switches
  done: true
- title: 'On initial buffer creation, each view applies its own default: list/ready/blocked → priority asc then id asc; closed → updated desc'
  done: true
- title: The sort transient has a 'reset to view default' suffix that re-applies the active view's default
  done: true
- title: The transient has a 'toggle direction' suffix that flips asc ↔ desc without re-picking the column
  done: true
- title: Header line shows the current sort key + direction alongside the view + filter chips (e.g. [ready] sort=priority↑ mode=afk)
  done: true
- title: Sorting is client-side over the existing CLI response — no extra subprocess per sort change
  done: true
- title: The built-in 'S' binding from tabulated-list-mode-map continues to sort by the column at point (no regression)
  done: true
---

## Description

Currently knot-list-mode inherits tabulated-list-mode's sort machinery: every column is declared sortable and the default key is `("ID" . nil)` (ascending). Knot ids are time-encoded ULIDs, so this is effectively chronological by creation. The default is reasonable for browsing all open tickets but wrong for triage views (ready / blocked) where priority should lead, and for closed where the user typically wants most-recently-touched on top.

## Design

- **State:** add a buffer-local `knot-list--sort` of shape `(KEY-SYMBOL . ASCENDING-P)` where KEY-SYMBOL is one of `id title priority status type mode created updated`.
- **Defaults table:** `knot-list--view-default-sort` mapping each of `(list ready blocked closed)` to its initial sort.
  - `list` → `(priority . t)` with id-asc tiebreak
  - `ready` → `(priority . t)` with id-asc tiebreak (matches `knot ready` server-side order)
  - `blocked` → `(priority . t)` with id-asc tiebreak
  - `closed` → `(updated . nil)` (descending)
- **Apply path:** `knot-list--render` consults `knot-list--sort` and feeds `tabulated-list-sort-key` before `tabulated-list-print`. The custom comparator handles secondary key (id-asc tiebreak) for priority sorts.
- **Transient:** new `knot-list-sort` bound to `o` (mnemonic 'order'; `s` is taken by slice 6's start). Suffixes for each key plus 'toggle direction' and 'reset to view default'.
- **Persistence:** sort state survives `knot-list--render` and view switches by living in the buffer-local, parallel to `knot-list--filters` (which already follow this pattern per slice 2).
- **Header line:** extend `knot-list--header-line` to append `sort=<key><↑|↓>` after the filter chips.

## Known limitations / out of scope

- **Sort by AC progress** is not in the key set: `knot list` rows don't carry an `acceptance` field (only `knot ready` does). A follow-up could either add CLI parity or compute progress from a side lookup.
- **CLI `--sort` flag.** This ticket is Emacs-only. Server-side sort parity is a separate option-(c) ticket if it ever becomes useful.
- **Multi-key sort UI.** Implementation uses a fixed id-asc tiebreak for priority sorts; a full multi-key picker is out of scope.

## Acceptance gotchas

- The built-in `S` binding from `tabulated-list-mode-map` must keep working — it currently mutates `tabulated-list-sort-key` directly, so the new buffer-local `knot-list--sort` should hydrate from `tabulated-list-sort-key` on render to stay consistent.

## Notes

**2026-05-13T01:23:28.297010240Z**

Shipped at emacs/knot.el. Sort transient bound to 'o' in knot-list-mode-map with single-key suffixes for id/title/priority/status/type/mode/created/updated plus 'd' toggle-direction and 'R' reset-to-view-default. Buffer-local knot-list--sort persists across filter changes, view switches, and 'g' refresh; per-view defaults (list/ready/blocked → priority asc + id-asc tiebreak; closed → updated desc) hydrate when unset. Sort is client-side over knot-list--rows; knot-list--render was split into fetch+rerender so sort changes never hit the CLI. Header-line shows 'sort=KEY↑|↓' alongside view + filter chips. Built-in 'S' interop preserved via knot-list--last-table-sort-key tracking — render hydrates only when the table key was externally mutated.
