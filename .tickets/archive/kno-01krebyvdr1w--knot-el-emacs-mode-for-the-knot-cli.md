---
id: kno-01krebyvdr1w
title: knot.el — Emacs mode for the knot CLI
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T15:12:48.056853378Z'
updated: '2026-05-13T00:26:07.660243649Z'
closed: '2026-05-13T00:26:07.660243649Z'
tags:
- emacs
- knot-el
acceptance:
- title: emacs/knot.el byte-compiles cleanly under Emacs 28.1
  done: true
- title: M-x knot opens a dispatch transient
  done: true
- title: list/ready/blocked/closed render in a single project-scoped buffer with l/r/b/c view switching
  done: true
- title: show buffer renders one ticket, ids are buttonized, RET on AC line flips done/undone
  done: true
- title: 'create transient + capital-C quick-create both work; post-create lands in show on ## Description'
  done: true
- title: update transient covers status/priority/mode/type/tags/assignee/parent without buffer pops
  done: true
- title: long-form capture buffers commit via C-c C-c (knot update / knot add-note) and cancel via C-c C-k
  done: true
- title: deps tree buffer renders JSON with status glyphs and buttonized nodes
  done: true
- title: knot-cli-call surfaces ok:false envelopes as user-error in the minibuffer
  done: true
- title: knot-info-current caches per directory and feeds transient completion sources
  done: true
- title: buffer names are project-qualified (multi-project safe)
  done: true
- title: bb lint:elisp task byte-compiles emacs/knot.el and runs package-lint
  done: true
deps:
- kno-01kreh3g266x
- kno-01kreh5wz1mb
- kno-01kreh5jjv7n
- kno-01kreh68sj1g
---

## Description

Build `emacs/knot.el`, a single-file magit-style Emacs mode that fronts the knot CLI: tabulated-list views for list/ready/blocked/closed, markdown-view show buffers with buttonized ids, transient menus for every mutation, capture buffers for long-form fields, dedicated deps-tree buffer. Project detection mirrors `magit-toplevel` via `knot info --json` (cached per directory). All CLI calls go through one boundary function that parses `--json` and signals `user-error` on `ok:false`. Lives in this monorepo at `emacs/knot.el` so CLI-contract changes move in lockstep with the Emacs consumer.

Full design: docs/prd/knot-el.md
ADRs: docs/adr/0001-knot-el-magit-style-ui.md, docs/adr/0002-knot-el-prefers-update-over-edit.md

Minimum surface for v0.1: dispatch transient (`M-x knot`); list/ready/blocked/closed in one buffer with view-switching; show + buttonized ids + AC line keymap; create transient + quick-create; per-field update transients; deps tree buffer; description/design/body/note capture buffers; `E` escape-hatch to `knot edit` via emacsclient.

## Notes

**2026-05-13T00:26:07.660243649Z**

v0.1 of emacs/knot.el delivered across 8 vertical slices (kno-01kreh377wy0, ...3g266x, ...4n6ryc, ...4yap1c, ...58t0ac, ...5wz1mb, ...5jjv7n, ...68sj1g). The mode ships M-x knot dispatch transient; project-scoped tabulated-list buffer with l/r/b/c view switching + filter transient + manual g refresh; markdown-view-mode show buffer with buttonized ids and AC RET/add/remove + dep/link remove-at-point; capital-C quick-create + create transient with per-flag readers landing in show on ## Description; per-field update transient covering status/priority/mode/type/tags/assignee/parent without buffer pops; capture buffers for description/design/body/add-note + capital-E emacsclient escape hatch; D/L transients for deps + links add/remove and t/tree-open into a dedicated knot-deps-mode JSON tree with status glyphs and buttonized nodes; knot-cli-call as the single CLI boundary surfacing ok:false envelopes as user-error; knot-info-current caching per directory and feeding transient completion sources; project-qualified buffer names everywhere (*knot-list: <project>*, *knot-show: <project> · <id>*, etc.); slice-8 cross-buffer refresh propagation and CLI version-compat lwarn against knot-minimum-cli-version 0.3.0; bb lint:elisp byte-compile + package-lint task wired in bb.edn. Follow-ups (still open as hitl): kno-01kremvgac07 evil bindings, kno-01kresz637ke list-row update transient, kno-01kretd4pqtn sort transient + view-specific default orderings, kno-01krf60j113x RET-on-frontmatter-field in show.
