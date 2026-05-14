---
id: kno-01krkhb71r1p
title: Bump knot.el baseline to Emacs 30.1 and migrate keymaps to keymap-set
status: open
type: chore
priority: 2
mode: afk
created: '2026-05-14T15:23:08.216043247Z'
updated: '2026-05-14T15:23:08.216043247Z'
tags:
- emacs
acceptance:
- title: Package-Requires declares (emacs "30.1")
  done: false
- title: All non-evil mode-map sites use keymap-set / keymap-unset
  done: false
- title: Evil compat table sites updated where appropriate, with kbd retained on evil-define-key* calls
  done: false
- title: knot.el byte-compiles cleanly on Emacs 30.1
  done: false
- title: bb test passes
  done: false
---

## Description

Bump knot.el's Package-Requires header from `(emacs "28.2")` to `(emacs "30.1")` and migrate all keymap construction from the pre-Emacs-29 `(define-key map (kbd "KEY") #'fn)` idiom to `keymap-set` / `keymap-unset`.

~50 call sites across five mode-maps (knot-list, knot-show, knot-capture, knot-deps, knot-prime/transients) plus the evil compat table. The evil sites use `evil-define-key*` and should retain `kbd` at those specific call sites; only the non-evil mode-map sites move to `keymap-set`.

Unblocks every other Emacs-30 follow-up ticket (the `emacs` tag).

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Tier 1).
