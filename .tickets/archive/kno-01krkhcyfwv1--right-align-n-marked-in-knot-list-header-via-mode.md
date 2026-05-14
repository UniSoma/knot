---
id: kno-01krkhcyfwv1
title: Right-align [N marked] in knot-list header via mode-line-format-right-align
status: closed
type: task
priority: 3
mode: afk
created: '2026-05-14T15:24:04.978452435Z'
updated: '2026-05-14T19:17:55.625098472Z'
closed: '2026-05-14T19:17:55.625098472Z'
tags:
- emacs
acceptance:
- title: '[N marked] right-aligned in knot-list header-line'
  done: true
- title: Filter chips remain left-aligned
  done: true
- title: Mark count is hidden when 0 (no stray right-aligned empty space)
  done: true
- title: bb test passes
  done: true
- title: Inline (space :align-to (- right N)) spec verified to right-align in header-line-format on Emacs 30.1 (helper variable proven unusable in this scope; see notes)
  done: true
deps:
- kno-01krkhb71r1p
---

## Description

`knot-list--header-line` (~line 779) left-aligns all chips including `[N marked]`. Inserting `mode-line-format-right-align` (Emacs 30.1) as a format element floats the mark count to the right edge, separating operational state from filter context (which stays left-aligned).

**Verification step before committing**: confirm `mode-line-format-right-align` is accepted inside `header-line-format` (not just `mode-line-format`) on Emacs 30.1. The Mastering Emacs writeup states it works in both, but this was not verified against Emacs 30.1 source in the investigation. A minimal `(setq header-line-format '("left" mode-line-format-right-align "right"))` test in a scratch buffer is sufficient.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 7, Gaps section).

## Notes

**2026-05-14T19:02:54.258746793Z**

Verification of AC1 FAILED on stock Emacs 30.1.

Empirical test in a real terminal frame (window-width 80):
- mode-line-format-right-align IN mode-line-format: align-to=70, rest-width=10 (correct)
- mode-line-format-right-align ONLY IN header-line-format: align-to=80, rest-width=0 (wrong; right content overflows off-screen)

Root cause: bindings.el:367 — mode--line-format-right-align hardcodes (memq 'mode-line-format-right-align mode-line-format). It does not consult the actual format list it is being :eval'd from. The Mastering Emacs writeup is incorrect for stock 30.1.

Pivot: implement right-alignment with an inline (space :align-to (- right N)) spec where N = (string-width marks-tag). Same user-visible result, no reliance on the broken helper. AC1 will be reframed accordingly.

**2026-05-14T19:17:55.625098472Z**

knot-list--header-line right-aligns [N marked] via an inline (space :align-to (- right N)) display spec inserted between filter chips and the marks tag. When no marks are set, no chunk and no stray space are emitted.

mode-line-format-right-align is unusable inside header-line-format on stock Emacs 30.1: the helper hardcodes (memq 'mode-line-format-right-align mode-line-format) in bindings.el:367, so when invoked from a header-line :eval context the trailing-width calculation returns 0 and content overflows. The inline spec sidesteps the helper entirely. Docstring records the workaround.
