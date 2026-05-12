---
id: kno-01kreh58t0ac
title: 'knot.el slice 5: capture buffers for long-form fields + add-note + edit escape hatch'
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:41.240481231Z'
updated: '2026-05-12T16:43:41.240481231Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: knot-capture mode carries target id, target field, and post-commit callback as buffer-locals
  done: false
- title: 'e in show pops *knot-edit-description: <project> · <id>* prefilled with the current description'
  done: false
- title: C-c C-c commits via knot update --description, --design, or --body with the buffer contents as one argv element (no shell escaping)
  done: false
- title: C-c C-k discards the buffer without writing
  done: false
- title: Design and body fields use the same capture mode via field-specific keys
  done: false
- title: n in show pops a capture buffer that pipes stdin to knot add-note <id> via call-process-region
  done: false
- title: Capital E in show shells out to knot edit <id> with EDITOR set to emacsclient
  done: false
- title: Successful commits auto-refresh the show buffer
  done: false
deps:
- kno-01kreh4n6ryc
---

## Description

Generic `knot-capture` minor mode plus its three concrete uses: long-form field edits (description / design / body), add-note, and the capital-E escape hatch to `knot edit` via emacsclient.

e in show pops a markdown-mode buffer prefilled with the current ## Description; C-c C-c commits via `knot update --description "..."` passing the buffer contents as a single argv element (no shell escaping); C-c C-k cancels. d and b similarly target design and body. n pops a buffer that pipes its content via stdin to `knot add-note <id>` using `call-process-region`.

Capital E shells out to `knot edit <id>` with EDITOR=emacsclient as the escape hatch for arbitrary structural edits. This is the only path that does NOT go through knot-cli-call's --json envelope, because knot edit is intentionally not --json-shaped.

Modules introduced: `knot-capture` (deep — parameterized by target id / field / post-commit callback).

See docs/prd/knot-el.md user stories 16-21, 'Editing model', and docs/adr/0002-knot-el-prefers-update-over-edit.md for why update + capture beats whole-file edit.
