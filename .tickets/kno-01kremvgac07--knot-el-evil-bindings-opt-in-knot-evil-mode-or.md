---
id: kno-01kremvgac07
title: knot.el evil bindings — opt-in knot-evil-mode or upstream to evil-collection
status: open
type: feature
priority: 3
mode: hitl
created: '2026-05-12T17:48:15.564075490Z'
updated: '2026-05-12T17:48:15.564075490Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
- evil
- v0.2
acceptance:
- title: 'Design decision recorded (Option A: in-tree knot-evil-mode, Option B: upstream to evil-collection, or both) with rationale'
  done: false
- title: 'If Option A: knot-evil-mode defined; evil is soft-required (only loaded when the minor mode is enabled); bindings cover at minimum gr=knot-refresh, ?=knot (dispatch), q=quit-window in evil normal state across knot-list-mode, knot-info-mode, and future knot-show-mode / knot-deps-mode'
  done: false
- title: 'If Option B: PR opened on emacs-evil/evil-collection adding knot.el bindings; tracking URL recorded in this ticket'
  done: false
- title: README (and/or docs/agents) gain an 'evil users' setup section pointing at the canonical path
  done: false
- title: bb lint:elisp still passes after the bindings land (no new byte-compile warnings, no new package-lint errors)
  done: false
---

## Description

evil-collection binds `g' as a prefix and `?' as a backward-search in `special-mode-map'. Both are inherited by `knot-list-mode' (via tabulated-list-mode → special-mode), so the plain `define-key' calls in knot.el slice 1 are shadowed for users running Doom Emacs + evil-collection (the default Doom stack).

The current workaround is documented per-user: rebind in evil normal-state from `config.el'. Sustainable answer: knot.el either ships an opt-in evil minor mode, OR the bindings live upstream in evil-collection.

## Two options to decide between

### Option A — `knot-evil-mode' (opt-in minor mode in knot.el)

- ✓ Bundles bindings with the package; users get them with `(knot-evil-mode +1)' (or via Doom's `use-package!' `:config').
- ✓ evil stays a soft-require — only loaded if the user toggles the minor mode.
- ✗ Every knot.el release becomes an evil release too; we own keymap conflicts.
- ✗ Adds another moving part to the package surface.

### Option B — submit bindings to evil-collection

- ✓ Canonical home for evil-specific keymaps; no evil code in knot.el.
- ✓ Ecosystem norm (magit, dired, vterm, transient itself — all live there).
- ✗ Out-of-tree: evil-collection users have to wait for an evil-collection release to pick up new knot.el bindings.
- ✗ External maintenance overhead (PR cycle, upstream review).

## Surface to bind (regardless of which option)

- `gr' → `knot-refresh' (replaces evil-collection's special-mode `gr' for revert; matches magit)
- `?' → `knot' (dispatch; restore the slice-1 binding)
- `q' → `quit-window' (restore special-mode default)
- `gg' / `G' → first/last line (let evil keep its defaults; nothing knot-specific)

Slice 3+'s show / deps / capture buffers will need the same treatment; design once, apply across all knot.el major modes.

## Out of scope

- Non-evil keybinding scheme — already shipped in slice 1, no change.
- Evilification of transient menus — transient routes through its own keymap, unaffected.
