---
id: kno-01krebyvdr1w
title: knot.el — Emacs mode for the knot CLI
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T15:12:48.056853378Z'
updated: '2026-05-12T15:12:48.056853378Z'
tags:
- emacs
- knot-el
acceptance:
- title: emacs/knot.el byte-compiles cleanly under Emacs 28.1
  done: false
- title: M-x knot opens a dispatch transient
  done: false
- title: list/ready/blocked/closed render in a single project-scoped buffer with l/r/b/c view switching
  done: false
- title: show buffer renders one ticket, ids are buttonized, RET on AC line flips done/undone
  done: false
- title: 'create transient + capital-C quick-create both work; post-create lands in show on ## Description'
  done: false
- title: update transient covers status/priority/mode/type/tags/assignee/parent without buffer pops
  done: false
- title: long-form capture buffers commit via C-c C-c (knot update / knot add-note) and cancel via C-c C-k
  done: false
- title: deps tree buffer renders JSON with status glyphs and buttonized nodes
  done: false
- title: knot-cli-call surfaces ok:false envelopes as user-error in the minibuffer
  done: false
- title: knot-info-current caches per directory and feeds transient completion sources
  done: false
- title: buffer names are project-qualified (multi-project safe)
  done: false
- title: bb lint:elisp task byte-compiles emacs/knot.el and runs package-lint
  done: false
---

## Description

Build `emacs/knot.el`, a single-file magit-style Emacs mode that fronts the knot CLI: tabulated-list views for list/ready/blocked/closed, markdown-view show buffers with buttonized ids, transient menus for every mutation, capture buffers for long-form fields, dedicated deps-tree buffer. Project detection mirrors `magit-toplevel` via `knot info --json` (cached per directory). All CLI calls go through one boundary function that parses `--json` and signals `user-error` on `ok:false`. Lives in this monorepo at `emacs/knot.el` so CLI-contract changes move in lockstep with the Emacs consumer.

Full design: docs/prd/knot-el.md
ADRs: docs/adr/0001-knot-el-magit-style-ui.md, docs/adr/0002-knot-el-prefers-update-over-edit.md

Minimum surface for v0.1: dispatch transient (`M-x knot`); list/ready/blocked/closed in one buffer with view-switching; show + buttonized ids + AC line keymap; create transient + quick-create; per-field update transients; deps tree buffer; description/design/body/note capture buffers; `E` escape-hatch to `knot edit` via emacsclient.
