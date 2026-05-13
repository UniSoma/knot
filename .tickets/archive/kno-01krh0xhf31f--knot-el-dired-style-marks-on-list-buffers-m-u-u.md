---
id: kno-01krh0xhf31f
title: 'knot.el: dired-style marks on list buffers (m/u/U)'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-13T15:57:34.051651367Z'
updated: '2026-05-13T17:01:57.360512940Z'
closed: '2026-05-13T17:01:57.360512940Z'
tags:
- emacs
- knot-el
acceptance:
- title: knot-list-mode-map binds m, u, U; m and u advance point one line and operate on the active region when use-region-p
  done: true
- title: Buffer-local knot-list--marks set survives refresh / filter / sort changes; cleared on view switch (l/r/b/c) and on U
  done: true
- title: Marked rows render * in the padding column and a new knot-marked face; header-line gains a [N marked] chunk while the set is non-empty
  done: true
- title: After every render, knot-list--marks is intersected with the rendered row set; stranded ids silently drop
  done: true
- title: knot-evil--bindings's knot-list-mode-map entry adds m / u / U so knot-evil-mode users get the same bindings via evil normal state
  done: true
- title: emacs/README.md binding table documents m / u / U with a prose paragraph describing the mark + bulk flow
  done: true
---

## Description

Add dired-style marking to `knot-list-mode` buffers so the user can build up a multi-row selection that subsequent bulk-aware commands operate on. Today every mutation action (`,` / `M`, `s`, `x`, `RET`) operates on the row at point only. With 16 live tickets and the bulk-update use case becoming common (set status / priority / mode / tags across N rows in one breath), the listing buffer needs a selection primitive. This slice ships the selection primitive end-to-end — visible glyphs, header indicator, region-aware marking, lifecycle rules — without any action consuming the marks yet. Slice 2 (separate ticket) wires the update transient.

## Design

**Storage.** Buffer-local `knot-list--marks` holding a set of ticket ids (list of strings, dedup at insertion). Owned by `knot-list-mode` buffers only.

**Bindings (`knot-list-mode-map`).**

- `m` — mark row at point (or every row in the active region when `use-region-p`); advance point one line.
- `u` — unmark row at point (or every row in the active region); advance one line.
- `U` — clear the entire `knot-list--marks` set.

`m`/`u` operate symmetrically on active regions; `U` is unconditional clear-all.

**Rendering.** `tabulated-list-padding` is already 1, so column 0 is free. After every `tabulated-list-print`, walk the buffer and re-paint marks: `*` glyph via `tabulated-list-put-tag` in column 0, and a new `knot-marked` face applied to the row's id column (or the whole row) so the selection is visible at a glance. Header-line gets a `[N marked]` chunk appended to the existing view/filter/sort chunk, present iff the set is non-empty.

**Lifecycle.** Marks survive refresh (`g`), filter changes, and sort changes — id is the stable identity, and the existing `knot-list--rerender` already preserves point on the previous row id. Marks clear on view switch (`l` / `r` / `b` / `c`) since the row set is conceptually different. After every render, intersect `knot-list--marks` with the current rendered row set; ids not in the set silently drop (handles tickets that disappear from view after a bulk mutation or from another agent's CLI write).

**Evil bindings.** `knot-evil--bindings`'s `knot-list-mode-map` entry gains `m` / `u` / `U` so `knot-evil-mode` users get the same affordance via evil normal state. `knot-evil--stock-keys` needs to strip any conflicting stock bindings (none in tabulated-list-mode for these letters today, but worth a check at install time).

**README.** `emacs/README.md` binding table gains the three new list-mode rows, plus a short prose paragraph describing the mark + bulk flow (forward-reference to slice 2 acceptable).

**No tests.** `knot.el` has no elisp test infrastructure yet; manual user-test via the binding walkthrough in the README. When elisp tests land (separate ticket), backfill coverage for the mark state transitions and the auto-prune contract.

**Out of scope.** Mark-all / toggle-marks / regex-mark commands; any action consuming the marks (see slice 2).

## Notes

**2026-05-13T17:01:57.360512940Z**

Shipped at emacs/knot.el + emacs/README.md. knot-list-mode gained a buffer-local knot-list--marks set, m/u/U bindings (region-aware m and u; U clears), * glyph via tabulated-list-put-tag plus a knot-marked face overlay on marked rows, a [N marked] header-line chunk, and lifecycle rules (intersected with rendered ids after every render so stranded ids drop; cleared on view switch and U). g rebound to knot-refresh in knot-list-mode-map so the visual repaint survives g (added to knot-evil--stock-keys for the evil g prefix). knot-evil--bindings's list-mode entry gains m/u/U. README binding table + prose paragraph documents the flow with a forward-reference to slice 2 (kno-01krh0y8g4sq). No tests — elisp test infra not yet in tree.
