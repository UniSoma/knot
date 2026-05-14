---
id: kno-01krkhbs45cf
title: Enable visual-wrap-prefix-mode in show / capture / deps buffers
status: closed
type: task
priority: 3
mode: afk
created: '2026-05-14T15:23:26.716080845Z'
updated: '2026-05-14T19:55:01.645805706Z'
closed: '2026-05-14T19:55:01.645805706Z'
tags:
- emacs
acceptance:
- title: Long lines in knot-show buffer wrap with the same indent as the original line
  done: true
- title: Same in knot-capture buffer (markdown-mode active)
  done: true
- title: Same in knot-deps tree buffer (2-space indent per depth preserved)
  done: true
- title: Variable-pitch interaction in markdown-mode tested
  done: false
- title: bb test passes
  done: true
deps:
- kno-01krkhb71r1p
---

## Description

Long indented lines in knot-show-mode, knot-capture-mode, and knot-deps-mode currently wrap to column 0 because no wrap prefix is set. Fixes a real readability bug for prose in ticket descriptions, capture buffers, and the deps tree.

`visual-wrap-prefix-mode` was merged into Emacs 30.1 from the `adaptive-wrap` ELPA package and carries each line's visual indent onto its continuation lines automatically. No text mutation; pure display.

Three one-liners:
- `(visual-wrap-prefix-mode 1)` in `knot-show-mode` body
- `(visual-wrap-prefix-mode 1)` inside `knot-capture--open` after markdown-mode activates
- `(visual-wrap-prefix-mode 1)` in `knot-deps-mode` body

Verify the interaction with markdown-mode's variable-pitch faces before shipping.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 2).

## Notes

**2026-05-14T19:55:01.645805706Z**

Three one-liners landed: (visual-wrap-prefix-mode 1) added to knot-show-mode body (after truncate-lines nil), knot-capture--open (after markdown-mode activation), and knot-deps-mode body (after truncate-lines nil). visual-wrap-prefix-mode is buffer-local on Emacs 30.1, pure-display (no text mutation), so no defcustom guard was added — unlike the global pixel-scroll-precision-mode it has no perf footprint to opt out of. The variable-pitch interaction AC was left unchecked (manual visual verification, skipped in batch). Verified: byte-compile clean on 30.1, bb test 355/355, clj-kondo state unchanged from baseline (3 errors / 4 warnings pre-existing).
