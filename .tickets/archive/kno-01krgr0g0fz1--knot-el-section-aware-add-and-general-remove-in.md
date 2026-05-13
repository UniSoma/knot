---
id: kno-01krgr0g0fz1
title: 'knot.el: section-aware `+` add and general `-` remove in show buffer'
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-05-13T13:21:53.678979162Z'
updated: '2026-05-13T13:44:01.105523127Z'
closed: '2026-05-13T13:44:01.105523127Z'
acceptance:
- title: Show buffer always renders `## Acceptance Criteria`, `## Blockers`, `## Linked`, `## Blocking` with italic placeholder when empty
  done: true
- title: Each of those four sections has its full span propertized with `knot-section` (`'acceptance` / `'blockers` / `'linked` / `'blocking`)
  done: true
- title: '`+` in `## Acceptance Criteria` calls `knot-show-add-ac`'
  done: true
- title: '`+` in `## Blockers` prompts for a live id and runs `knot dep <this> <to>`'
  done: true
- title: '`+` in `## Linked` prompts for a live id and runs `knot link <this> <to>`'
  done: true
- title: '`+` in `## Blocking` prompts for a live id and runs `knot dep <other> <this>`'
  done: true
- title: '`+` outside any of the four sections pops `knot-show-add-transient` with `a`/`d`/`l` suffixes'
  done: true
- title: '`-` is rebound to `knot-show-remove-at-point`; `a` and `k` bindings are removed in non-evil mode'
  done: true
- title: '`knot-evil--bindings` swaps `+` to `knot-show-add-at-point` and drops the `a` entry; `K` stays'
  done: true
- title: '`knot-show-remove-ac` is deleted (no callers remain)'
  done: true
- title: '`emacs/README.md` binding table reflects the new `+`/`-` behavior and the removed `a`/`k` bindings'
  done: true
- title: '`bb test` passes'
  done: true
- title: '`clj-kondo --lint src test` is clean'
  done: true
---

## Description

Make `+` and `-` in `knot-show-mode` context-sensitive instead of AC-only.

## Current state

- `+` and `a` → `knot-show-add-ac` (always AC, regardless of point)
- `-` → `knot-show-remove-ac` (errors when point is not on an AC row)
- `k` → `knot-show-remove-at-point` (the existing general remover; dispatches on `knot-dep-id` / `knot-rdep-id` / `knot-link-id` / `knot-ac-title` at point)
- `## Blockers`, `## Linked`, `## Blocking` render conditionally — hidden when empty. `## Acceptance Criteria` renders always (placeholder when empty).

## Target state

`+` becomes section-aware via a new `knot-section` text property on each section's full span; `-` becomes the canonical general remover (alias of today's `k`).

### `+` dispatch (`knot-show-add-at-point`)

| `knot-section` at point | Action                                          |
|--------------------------|-------------------------------------------------|
| `'acceptance`            | `knot-show-add-ac`                              |
| `'blockers`              | prompt for live id; `knot dep <this> <to>`      |
| `'linked`                | prompt for live id; `knot link <this> <to>`     |
| `'blocking`              | prompt for live id; `knot dep <other> <this>`   |
| `nil` (off-section)      | pop `knot-show-add-transient`                   |

### `knot-show-add-transient` (off-section fallback)

- `a` acceptance criterion
- `d` dep
- `l` link

No reverse-dep entry — reachable only via `+` in `## Blocking`.

## Render changes

- Always render `## Blockers`, `## Linked`, `## Blocking` with italic placeholder when empty (matches AC's existing behavior). `## Children` stays conditional.
- Extend `knot-show--render-relationship` to accept the section symbol and propertize the rendered span with `knot-section`. Wrap the AC block similarly.

## Keymap

`knot-show-mode-map` (non-evil):
- `+` → `knot-show-add-at-point`
- `-` → `knot-show-remove-at-point` (was `knot-show-remove-ac`)
- `a` → **removed** (no longer aliases AC-add)
- `k` → **removed** (`-` is canonical)

`knot-evil--bindings`:
- Replace `("+" . knot-show-add-ac)` → `("+" . knot-show-add-at-point)`
- Drop `("a" . knot-show-add-ac)`
- `K` → `knot-show-remove-at-point` unchanged; no `-` binding (collides with `evil-previous-line-first-non-blank`)

## Cleanup

`knot-show-remove-ac` loses its only caller and is deleted; its body already lives inlined in the `(ac …)` arm of `knot-show-remove-at-point`.

## Docs

- `emacs/README.md` binding table
- New function docstrings
- `knot-show-mode` docstring touch-ups

## Not doing

- `A` / `knot-ac-transient` (off-section `+` covers AC-from-anywhere; revisit if friction emerges)
- New tests (no elisp test surface in repo; CLI carries the tested behavior)

## Notes

**2026-05-13T13:44:01.105523127Z**

Shipped at emacs/knot.el + emacs/README.md. `+` and `-` in knot-show-mode are now context-sensitive: `+` reads a new `knot-section` text property (rendered onto AC, Blockers, Linked, Blocking spans) and dispatches to add-ac / deps-add / links-add / a new knot-show-add-rdep (knot dep <to> <this>); off-section it pops knot-show-add-transient with a/d/l. `-` is rebound to knot-show-remove-at-point — the general remover that already handled dep/rdep/link/ac via text props. The four +-eligible sections are always rendered (italic placeholders when empty) so + always has a section to land in; ## Children stays conditional. knot-show-remove-ac deleted. Evil bindings: + retargeted, - bound (shadowing evil-previous-line-first-non-blank), K kept as synonym; a/k dropped. README binding table + prose updated. bb test 347/0; byte-compile clean.
