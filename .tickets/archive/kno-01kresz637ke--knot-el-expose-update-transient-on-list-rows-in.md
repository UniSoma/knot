---
id: kno-01kresz637ke
title: 'knot.el: expose update transient on list rows ('','' in list)'
status: closed
type: feature
priority: 3
mode: hitl
created: '2026-05-12T19:17:39.039590208Z'
updated: '2026-05-13T02:51:35.586842690Z'
closed: '2026-05-13T02:51:35.586842690Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: From a list row, knot-update-from-show opens via ',' (non-evil) and via 'M' under knot-evil-mode; knot-update--ticket-id and knot-update--current-field are refactored to resolve id (tabulated-list-get-id) and defaults (one-shot knot show --json) from knot-list-mode
  done: true
- title: The Long-form group (e d b n) is conditional on knot-show-mode and hidden when the transient opens from a list row; the frontmatter group (s p m t T a P) is unchanged
  done: true
- title: Each commit is exactly one knot update --flag value subprocess (atomic — no batching); ok:false envelopes raise user-error and leave list buffer state unchanged
  done: true
- title: After a successful commit, knot--after-mutation re-renders the list buffer with point preserved on the originating row id (no new list-side refresh code needed)
  done: true
- title: knot-evil--bindings's knot-list-mode-map entry includes ("M" . knot-update-from-show); emacs/README.md binding table gains the new list-mode rows (',' / 'M' for the Modify transient)
  done: true
deps:
- kno-01kreh4yap1c
---

## Description

Slice 4 (kno-01kreh4yap1c) put the atomic frontmatter update transient at `,` in show buffers; slice 9 (kno-01kremvgac07) added `knot-evil-mode`, rebinding the show-buffer entry point to `M` under evil and folding long-form `e d b n` into the same transient as a second group. Per the originating PRD story (bumping priority without context-switching) and parity with `s` / `x` and the deps / links transients — all of which work on list rows and show — the frontmatter transient should reach list rows too.

## Design

**Reuse the existing transient.** `knot-update-from-show` becomes source-aware rather than spawning a sibling `knot-update-from-list`. The name stays for backwards compatibility with the Doom snippet in `emacs/README.md`.

**Source-aware resolvers.**

- `knot-update--ticket-id`: in `knot-show-mode` returns `knot-show--id`; in `knot-list-mode` returns `(tabulated-list-get-id)`; signals `user-error` outside these modes.
- `knot-update--current-field`: in `knot-show-mode` reads from `knot-show--data` (today's behavior); in `knot-list-mode` does a one-shot `knot show <id> --json` to populate defaults, since list rows do not carry every field (tags / parent / assignee may be absent from the row).

**Hide Long-form in list.** The Long-form group (`e` description, `d` design, `b` body, `n` note) is conditional on `knot-show-mode` (`:if-derived knot-show-mode` or equivalent). From a list row only the frontmatter group (`s p m t T a P`) shows — long-form requires drilling into the ticket first via `RET`.

**Bindings.**

- `knot-list-mode-map`: bind `,` to `knot-update-from-show` (mirrors the non-evil show-buffer binding).
- `knot-evil--bindings`'s `knot-list-mode-map` entry: add `("M" . knot-update-from-show)` so the evil normal-state binding mirrors show under `knot-evil-mode`.

**Refresh.** `knot-update--commit` already calls `knot--after-mutation`, which walks every visible knot.el buffer for the project and re-renders. The list buffer's existing render path (`knot-list--rerender`) preserves point on the originating row id, so no list-side changes are needed beyond the binding.

**Out of scope.** Status transitions on list rows (already covered by slice 6's standalone `s` / `x`); long-form edits from list rows (drill into show first).

## Notes

**2026-05-13T02:51:35.586842690Z**

Shipped at emacs/knot.el + emacs/README.md. knot-update--ticket-id and knot-update--current-field are now source-aware: knot-show-mode reads knot-show--id / knot-show--data, knot-list-mode reads tabulated-list-get-id and a one-shot knot show --json. knot-list-mode-map binds ',' to knot-update-from-show; knot-evil--stock-keys strips that ',' and knot-evil--bindings adds 'M' for evil normal state. The Long-form group gains :if-derived knot-show-mode so list-row invocations only see frontmatter (s p m t T a P). Commit semantics and refresh path unchanged — one knot update --flag value subprocess via knot-update--commit, then knot--after-mutation → knot-list--rerender preserves point on the originating row id. README binding table + prose updated to cover the list-mode entry point.
