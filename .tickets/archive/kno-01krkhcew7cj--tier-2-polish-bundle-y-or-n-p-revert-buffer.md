---
id: kno-01krkhcew7cj
title: 'Tier-2 polish bundle: y-or-n-p, revert-buffer wiring, pop-to-buffer-same-window, which-key README'
status: closed
type: chore
priority: 3
mode: afk
created: '2026-05-14T15:23:48.989225278Z'
updated: '2026-05-14T19:33:49.715404818Z'
closed: '2026-05-14T19:33:49.715404818Z'
tags:
- emacs
acceptance:
- title: Four remove confirmations use y-or-n-p
  done: true
- title: M-x revert-buffer and `g` rerender the knot list buffer via tabulated-list-revert-hook
  done: true
- title: knot-show-quit and knot-deps-quit use pop-to-buffer-same-window
  done: true
- title: README mentions which-key-mode for transient discoverability
  done: true
- title: bb test passes
  done: true
deps:
- kno-01krkhb71r1p
---

## Description

Four small modernizations grouped because each is a one-or-two-line change with the same shape (modern Emacs idiom replacing legacy one):

1. **y-or-n-p for remove confirmations** — at ~lines 1595, 1599, 1604, 1608 (dep, rdep, link, acceptance-criterion removals), `yes-or-no-p` forces the user to spell out 'yes' / 'no'. All four operations are CLI-recoverable; downgrade to `y-or-n-p`.

2. **Wire knot-list--render to tabulated-list-revert-hook** — `M-x revert-buffer` and `g` currently do nothing useful in the knot list buffer. Wiring `knot-list--render` to the hook makes `g` and revert-buffer synonymous with the existing refresh. Confirm exact variable name with `describe-variable` first.

3. **pop-to-buffer-same-window in knot-show-quit and knot-deps-quit** — these are the only two places in the file still using bare `switch-to-buffer` for knot-to-knot navigation; the rest already uses `pop-to-buffer-same-window`, which respects `display-buffer-alist`.

4. **README which-key note** — one sentence: 'On Emacs 30.1, enable `(which-key-mode 1)` for free transient discoverability.' No package changes; built-in which-key intercepts partial keypresses and renders the existing transient group labels as popups.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 3, Theme 7).

## Notes

**2026-05-14T19:33:49.715404818Z**

Tier-2 polish bundle landed: (1) four remove confirmations in knot-show-remove-at-point switched from yes-or-no-p to y-or-n-p (dep, rdep, link, ac); (2) tabulated-list-revert-hook now wired to knot-list--render as a buffer-local hook in knot-list-mode body, so M-x revert-buffer and `g` both refresh; (3) knot-show-quit and knot-deps-quit switched bare switch-to-buffer to pop-to-buffer-same-window for display-buffer-alist friendliness; (4) emacs/README.md gains a Transient discoverability subsection (between Smooth scrolling and Evil/Doom) recommending (which-key-mode 1) on Emacs 30.1. Verified: bb lint:elisp clean (byte-compile + package-lint), bb test 355/355.
