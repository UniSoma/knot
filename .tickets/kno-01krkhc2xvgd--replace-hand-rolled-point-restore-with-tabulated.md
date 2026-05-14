---
id: kno-01krkhc2xvgd
title: Replace hand-rolled point restore with tabulated-list-goto-id
status: open
type: task
priority: 3
mode: afk
created: '2026-05-14T15:23:36.754334684Z'
updated: '2026-05-14T15:23:36.754334684Z'
tags:
- emacs
acceptance:
- title: knot-list--rerender uses tabulated-list-goto-id with fallback to point-min
  done: false
- title: Two secondary sites audited (updated where straightforward, comment-tagged where semantically distinct)
  done: false
- title: Point lands on the same row after rerender for filter/sort changes
  done: false
- title: No visible delay on lists of hundreds of tickets
  done: false
- title: bb test passes
  done: false
deps:
- kno-01krkhb71r1p
---

## Description

knot-list--rerender scans every row from `(point-min)` to restore point by id after a rerender. `tabulated-list-goto-id` was added in Emacs 29 specifically to replace this pattern. Replacement is one expression:

  (or (tabulated-list-goto-id prev-id) (goto-char (point-min)))

Two secondary sites have the same hand-rolled pattern with slightly different semantics:
- `knot-show--step` (~lines 1659–1670)
- `knot-list--marks-in-display-order` (~lines 2167–2173)

For these, tabulated-list-goto-id can still anchor the search; a single `forward-line` after the anchor reaches the target. Audit each and either update or leave with a brief comment explaining why the linear scan is semantically required.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 4).
