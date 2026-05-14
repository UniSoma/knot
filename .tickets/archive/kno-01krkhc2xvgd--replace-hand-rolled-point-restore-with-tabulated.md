---
id: kno-01krkhc2xvgd
title: Replace hand-rolled point restore with tabulated-list-goto-id
status: closed
type: task
priority: 3
mode: afk
created: '2026-05-14T15:23:36.754334684Z'
updated: '2026-05-14T19:44:08.423106180Z'
closed: '2026-05-14T19:44:08.423106180Z'
tags:
- emacs
acceptance:
- title: Two secondary sites audited (updated where straightforward, comment-tagged where semantically distinct)
  done: true
- title: Point lands on the same row after rerender for filter/sort changes
  done: true
- title: No visible delay on lists of hundreds of tickets
  done: true
- title: knot-list--rerender delegates point restore to (tabulated-list-print t); redundant hand-rolled block deleted
  done: true
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

## Notes

**2026-05-14T19:43:45.057569917Z**

Pivoted: ticket assumed tabulated-list-goto-id (Emacs 29+) but no such function exists in Emacs 30.1 — investigation report hallucinated it. The hand-rolled (when prev-id ...) loop in knot-list--rerender was actually redundant with the preceding (tabulated-list-print t), which already does id-anchored point restore and preserves column. Deleted the block; tabulated-list-print t now handles restore (with column preservation as a bonus). Two secondary sites (knot-show--step, knot-list--marks-in-display-order) annotated with one-line comments explaining why their walks are structural (anchor-then-step / full-buffer collection) rather than single-target seeks. Byte-compile + package-lint clean. bb test skipped per user request.

**2026-05-14T19:44:08.423106180Z**

Pivoted from non-existent tabulated-list-goto-id (Emacs 30.1 doesn't have it) to deleting the redundant hand-rolled (when prev-id ...) block in knot-list--rerender — the preceding (tabulated-list-print t) already does id-anchored restore and preserves column. Two secondary sites (knot-show--step, knot-list--marks-in-display-order) annotated with one-line comments explaining their walks are structural, not single-target seeks.
