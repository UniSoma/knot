---
id: kno-01krkhd9x5fs
title: Rich completing-read annotations for ticket pickers
status: closed
type: feature
priority: 3
mode: afk
created: '2026-05-14T15:24:16.667513065Z'
updated: '2026-05-14T18:42:37.106033769Z'
closed: '2026-05-14T18:42:37.106033769Z'
tags:
- emacs
acceptance:
- title: knot--annotate-ticket-candidate helper defined and unit-tested
  done: false
- title: Hash table keyed by id is built once per picker call (no per-candidate linear scan)
  done: true
- title: Four picker call sites annotate with status glyph + priority + type
  done: true
- title: Status glyphs match knot-deps--status-glyph output
  done: true
- title: No noticeable lag on lists of hundreds of tickets
  done: true
- title: bb test passes
  done: true
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

## Notes

**2026-05-14T18:34:00.772782812Z**

AC#1 'unit-tested' half intentionally skipped per user instruction ('Skip tests'). Helper is defined and smoke-tested manually via emacs -Q batch load against synthetic rows; output matches the expected (CANDIDATE PREFIX SUFFIX) shape with ○/✓ glyphs and propertized 'completions-annotations' suffix.

**2026-05-14T18:42:37.106033769Z**

Shipped 3 shared helpers in emacs/knot.el (knot--ticket-annotation-table / knot--annotate-ticket-candidate / knot--ticket-affixation-function) and wired :affixation-function into 4 picker call sites: knot-deps--read-live, knot-deps--read-current, knot-update--read-parent, knot-create--read-id-list (CRM). Candidates now show status glyph prefix (○/✓ via knot-deps--status-glyph) + 'p<priority> <type>' suffix in completions-annotations face. Hash table built once per picker call (O(1) per-candidate). Refactored knot-deps--live-choices → live-rows + rows->choices, and id->title-map → id->row-map so the affixation table reuses the same fetch. Verified via bb lint:elisp (byte-compile + package-lint clean), bb test (355 tests, 0 failures), and emacs -Q smoke-load against synthetic rows. AC#1 unit-tested half intentionally skipped per user instruction; manual smoke confirms helper shape.
