---
id: kno-01krkhd9x5fs
title: Rich completing-read annotations for ticket pickers
status: open
type: feature
priority: 3
mode: afk
created: '2026-05-14T15:24:16.667513065Z'
updated: '2026-05-14T15:24:16.667513065Z'
tags:
- emacs
acceptance:
- title: knot--annotate-ticket-candidate helper defined and unit-tested
  done: false
- title: Hash table keyed by id is built once per picker call (no per-candidate linear scan)
  done: false
- title: Four picker call sites annotate with status glyph + priority + type
  done: false
- title: Status glyphs match knot-deps--status-glyph output
  done: false
- title: No noticeable lag on lists of hundreds of tickets
  done: false
- title: bb test passes
  done: false
deps:
- kno-01krkhb71r1p
---

## Description

Four ticket-picker `completing-read` sites currently present bare `id  title` strings:
- `knot-deps--read-live`
- `knot-update--read-parent`
- `knot-create--read-id-list`
- `knot-deps--read-current`

Use `completion-extra-properties` with `:affixation-function` to surface status, priority, and type inline — all data is already present in the `knot list --json` response each picker fetches.

Approach:
- Add `knot--annotate-ticket-candidate`, a shared helper backed by an id→alist hash (built from the same JSON response). Reuse the existing `knot-deps--status-glyph` (○ / ◉ / ✓) as the prefix glyph for visual consistency with the deps tree.
- Each call site wraps its `completing-read` in a `let` that binds `completion-extra-properties` to a property list with `:affixation-function #'knot--annotate-ticket-candidate`.
- Affixation returns a 3-element list per candidate: `(prefix-glyph candidate-string right-margin-annotation)`.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 5).
