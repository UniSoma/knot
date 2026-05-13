---
id: kno-01kremvgac07
title: knot.el evil bindings — opt-in knot-evil-mode or upstream to evil-collection
status: open
type: feature
priority: 3
mode: hitl
created: '2026-05-12T17:48:15.564075490Z'
updated: '2026-05-13T02:11:00.443929560Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
- evil
- v0.2
acceptance:
- title: knot-evil-mode defined in knot.el as a :global t opt-in minor mode; evil is a soft require ((require 'evil nil t) at load); enabling the mode runs an idempotent setup that (a) mutates each knot-*-mode-map to drop colliding bindings and (b) adds the new bindings via evil-define-key into the per-mode auxiliary map for evil normal state
  done: false
- title: 'evil-set-initial-state wired: normal for knot-list-mode / knot-show-mode / knot-info-mode / knot-deps-mode; insert for knot-capture-mode'
  done: false
- title: Binding table from this ticket applied in full — global (? gr q), list (RET f o s x D L), show (RET + a - s x M [ ] K D L E), deps (TAB <backtab> f q ? gr); the M transient gains long-form suffixes e/d/b/n alongside existing frontmatter s/p/m/t/T/a/P
  done: false
- title: README (or emacs/README.md) gains an 'Evil / Doom users' section with the paste-ready use-package! knot block including (knot-evil-mode +1) and the map! :localleader snippet
  done: false
- title: bb lint:elisp passes with no new byte-compile warnings or package-lint errors after the changes
  done: false
- title: 'Manual smoke in a Doom session: every listed binding fires correctly in evil normal state across all four read-only knot major modes; buffers open in normal state automatically'
  done: false
---

## Description

Adds an opt-in `knot-evil-mode` to `emacs/knot.el` so the slice-1 bindings work cleanly in evil normal state — primarily for Doom Emacs users. The current bindings collide with evil motions (`l r b c n k e b p`) and operators (`s x c d a o`), plus `?` (search-backward) and `g` (prefix).

## Resolved design

**Audience.** Personal project, small group of friends — upstreaming to `emacs-evil/evil-collection` is overkill. Ship in-tree only.

**Philosophy: Doom-aware Pragmatic-C.** Preserve evil motions (`h j k l w b e 0 $ gg G n /`); claim evil operators (`s x c d a o` — text-mutation, no-ops in read-only knot buffers). Magit's precedent. One specific exception: relocate `,` → `M` because Doom users typically alias `,` to localleader, and the user's evil-snipe also binds it.

**View-switching consolidation.** Buffer-local view bindings (`l r b c`) in `knot-list-mode` are removed — the existing `?` dispatch transient already exposes Views; one indirection is enough.

**Long-form edit consolidation.** Buffer-local `e d b n` (edit-description/design/body, add-note) fold into the renamed `M` transient (formerly `knot-update-from-show` on `,`). Single transient owns every field mutation.

**Activation: destructive (evil-collection style).** When `knot-evil-mode` is enabled, the setup function (a) mutates each `knot-*-mode-map` to drop colliding bindings and (b) adds the new bindings via `evil-define-key` against the per-mode auxiliary map for `normal` state. Idempotent — re-running is a no-op. Toggle-off is a no-op (no symmetric restore); reload `knot.el` to revert.

**State strategy.**
- `evil-set-initial-state` → `normal` for `knot-list-mode`, `knot-show-mode`, `knot-info-mode`, `knot-deps-mode`.
- `knot-capture-mode` → `insert` (existing `C-c C-c` / `C-c C-k` are state-agnostic).
- All bindings live in `normal` state only (no visual — no bulk-action workflow).

**Soft require.** `(require 'evil nil t)` at load; bindings only wired when `knot-evil-mode` is toggled on. Vanilla-Emacs users see no evil code paths.

## Binding table

### Global (all read-only modes)
- `?` → `knot` dispatch (overrides `evil-search-backward`)
- `gr` → `knot-refresh` (magit/dired/info convention)
- `q` → context quit (`quit-window` / `knot-show-quit` / `knot-deps-quit`)

### `knot-list-mode`
- `RET` show-at-point
- `f` filter, `o` sort
- `s` start, `x` close
- `D` deps-transient, `L` links-transient
- Removed: `l r b c` (use `?` dispatch); standalone `F` clear-filters (reachable via `f C` inside the filter transient)

### `knot-show-mode`
- `RET` context action
- `+` / `a` add-ac, `-` remove-ac
- `s` start, `x` close
- `M` update transient (extended with `e` description, `d` design, `b` body, `n` note alongside existing frontmatter suffixes `s p m t T a P`)
- `[` / `]` prev/next ticket
- `K` remove-at-point (relocated from `k`)
- `D` deps, `L` links, `E` emacsclient-edit
- Removed: standalone `, e d b n p k` (folded into `M` or evil-restored)

### `knot-info-mode`
Global bindings only.

### `knot-deps-mode`
- `TAB` / `<backtab>` navigation
- `f` toggle-full
- `q` quit
- `?` dispatch, `gr` refresh

## Doom localleader integration

Not auto-wired — avoids a hard `map!` macro dependency from `knot.el`. README ships a paste-ready snippet:

```elisp
(use-package! knot
  :commands (knot knot-list knot-show)
  :config
  (knot-evil-mode +1)
  (map! :map (knot-list-mode-map knot-show-mode-map
              knot-info-mode-map knot-deps-mode-map)
        :localleader
        :desc "Dispatch"  "?" #'knot
        :desc "Refresh"   "r" #'knot-refresh
        :desc "Start"     "s" #'knot-start
        :desc "Close"     "x" #'knot-close
        :desc "Modify"    "M" #'knot-update-from-show
        :desc "Deps"      "D" #'knot-deps-transient
        :desc "Links"     "L" #'knot-links-transient)
  (map! :map knot-list-mode-map
        :localleader
        :desc "Filter"    "f" #'knot-list-filter
        :desc "Sort"      "o" #'knot-list-sort))
```

## Out of scope

- Upstream PR to `emacs-evil/evil-collection` (deferred indefinitely — audience too small).
- Visual-state bindings (no bulk-action workflow).
- Doom detection inside `knot-evil-mode` (snippet is paste-yourself).
- `knot-capture-mode` keybinding changes (`C-c C-c` / `C-c C-k` are already state-agnostic).
- Non-evil keybinding scheme (slice 1 unchanged when `knot-evil-mode` is off).