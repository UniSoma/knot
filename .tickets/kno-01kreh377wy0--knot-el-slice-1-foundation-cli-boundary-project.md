---
id: kno-01kreh377wy0
title: 'knot.el slice 1: foundation — CLI boundary, project detection, dispatch, list view'
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:42:34.108334227Z'
updated: '2026-05-12T16:42:34.108334227Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: emacs/knot.el lives at emacs/knot.el with proper Package-Requires headers (Emacs 28.1, markdown-mode 2.5), Commentary block, and matching license header
  done: false
- title: bb lint:elisp task byte-compiles emacs/knot.el cleanly and runs package-lint when available
  done: false
- title: knot-cli-call runs the knot binary synchronously via call-process, parses --json into a plist/alist, returns data on ok:true, signals user-error with the envelope's error message on ok:false
  done: false
- title: knot-info-current returns the cached info envelope per default-directory; knot-info-allowed-values and knot-info-defaults expose the relevant fields
  done: false
- title: M-x knot opens a dispatch transient with entries for list/ready/blocked/closed, create, quick-create, info, refresh; ? inside any knot.el buffer reopens it
  done: false
- title: 'knot-list renders the default list view in *knot-list: <project>* with the same columns as knot list (id, title, type, priority, mode, status, optional AC progress)'
  done: false
- title: buffer captures project root as its buffer-local default-directory; buffer name is project-qualified
  done: false
- title: q calls quit-window from any knot.el buffer
  done: false
---

## Description

Foundation slice: bring up the core machinery that every other slice depends on. Single CLI boundary, project detection mirroring magit-toplevel, dispatch transient as the entry point, and a basic list-view buffer.

After this slice, `M-x knot` opens the dispatch transient and `l` lands the user in a tabulated list of tickets for the current project. Everything is sync via `call-process`; all reads use `--json`; `ok:false` envelopes raise `user-error`.

Modules introduced: `knot-cli`, `knot-info`, `knot-id` (display half — buttonize half lands in slice 3), `knot-format`, `knot-dispatch`, `knot-list` (default view only — slice 2 adds view switching). Package metadata, autoloads, customization group, and the `bb lint:elisp` task land here so future slices inherit the lint discipline.

See docs/prd/knot-el.md (sections 'Repository layout', 'CLI invocation', 'Project detection', 'Buffer architecture', 'Modules', 'Naming & packaging', 'Lint discipline') for the design rationale.

Out of scope for this slice: view switching (l/r/b/c), filter transient, show buffer, all mutations, deps tree, capture buffers, version-compat warning.
