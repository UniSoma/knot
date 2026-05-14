---
id: kno-01krkhb71r1p
title: Bump knot.el baseline to Emacs 30.1 and migrate keymaps to keymap-set
status: closed
type: chore
priority: 2
mode: afk
created: '2026-05-14T15:23:08.216043247Z'
updated: '2026-05-14T18:07:39.414275441Z'
closed: '2026-05-14T18:07:39.414275441Z'
tags:
- emacs
acceptance:
- title: Package-Requires declares (emacs "30.1")
  done: true
- title: All non-evil mode-map sites use keymap-set / keymap-unset
  done: true
- title: Evil compat table sites updated where appropriate, with kbd retained on evil-define-key* calls
  done: true
- title: knot.el byte-compiles cleanly on Emacs 30.1
  done: true
- title: bb test passes
  done: true
---

## Description

Bump knot.el's Package-Requires header from `(emacs "28.2")` to `(emacs "30.1")` and migrate all keymap construction from the pre-Emacs-29 `(define-key map (kbd "KEY") #'fn)` idiom to `keymap-set` / `keymap-unset`.

~50 call sites across five mode-maps (knot-list, knot-show, knot-capture, knot-deps, knot-prime/transients) plus the evil compat table. The evil sites use `evil-define-key*` and should retain `kbd` at those specific call sites; only the non-evil mode-map sites move to `keymap-set`.

Unblocks every other Emacs-30 follow-up ticket (the `emacs` tag).

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Tier 1).

## Notes

**2026-05-14T18:07:39.414275441Z**

knot.el baseline bumped to Emacs 30.1; ~53 non-evil mode-map sites migrated to keymap-set across knot-info/list/show/capture/deps; evil compat strip-bindings uses keymap-unset (parent-shadow semantic preserved); evil-define-key* retains kbd. Also fixed a pre-existing docstring quoting issue (single quotes around 'knot-create) flagged by Emacs 30's stricter byte-compiler. Verified: byte-compile clean on 30.1, bb test 355/355, clj-kondo state unchanged from baseline.
