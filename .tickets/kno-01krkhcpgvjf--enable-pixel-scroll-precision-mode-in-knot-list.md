---
id: kno-01krkhcpgvjf
title: Enable pixel-scroll-precision-mode in knot-list and knot-deps buffers
status: open
type: task
priority: 3
mode: afk
created: '2026-05-14T15:23:56.817916040Z'
updated: '2026-05-14T15:23:56.817916040Z'
tags:
- emacs
acceptance:
- title: knot-pixel-scroll defcustom defined with default t and docstring noting CPU caveat
  done: false
- title: Pixel scroll active in knot-list and knot-deps buffers when t
  done: false
- title: Standard line scroll when nil
  done: false
- title: README documents the opt-out
  done: false
- title: bb test passes
  done: false
deps:
- kno-01krkhb71r1p
---

## Description

Both `knot-list-mode` and `knot-deps-mode` scroll in full-line jumps. Enabling `pixel-scroll-precision-mode` (Emacs 29.1) buffer-locally makes trackpad scrolling feel native.

Add a `knot-pixel-scroll` defcustom (default `t`) for opt-out — the feature has a known CPU-intensity caveat on some setups and the defcustom gives users a documented switch.

When the defcustom is `t`, enable `pixel-scroll-precision-mode` locally in the two mode bodies (or via mode hooks).

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 7).
