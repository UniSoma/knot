---
id: kno-01krkhcyfwv1
title: Right-align [N marked] in knot-list header via mode-line-format-right-align
status: open
type: task
priority: 3
mode: afk
created: '2026-05-14T15:24:04.978452435Z'
updated: '2026-05-14T15:24:04.978452435Z'
tags:
- emacs
acceptance:
- title: mode-line-format-right-align verified to work inside header-line-format on Emacs 30.1
  done: false
- title: '[N marked] right-aligned in knot-list header-line'
  done: false
- title: Filter chips remain left-aligned
  done: false
- title: Mark count is hidden when 0 (no stray right-aligned empty space)
  done: false
- title: bb test passes
  done: false
deps:
- kno-01krkhb71r1p
---

## Description

`knot-list--header-line` (~line 779) left-aligns all chips including `[N marked]`. Inserting `mode-line-format-right-align` (Emacs 30.1) as a format element floats the mark count to the right edge, separating operational state from filter context (which stays left-aligned).

**Verification step before committing**: confirm `mode-line-format-right-align` is accepted inside `header-line-format` (not just `mode-line-format`) on Emacs 30.1. The Mastering Emacs writeup states it works in both, but this was not verified against Emacs 30.1 source in the investigation. A minimal `(setq header-line-format '("left" mode-line-format-right-align "right"))` test in a scratch buffer is sufficient.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 7, Gaps section).
