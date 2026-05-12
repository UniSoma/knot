---
id: kno-01kreh58t0ac
title: 'knot.el slice 5: capture buffers for long-form fields + add-note + edit escape hatch'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:41.240481231Z'
updated: '2026-05-12T19:55:05.833790136Z'
closed: '2026-05-12T19:55:05.833790136Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: knot-capture mode carries target id, target field, and post-commit callback as buffer-locals
  done: true
- title: 'e in show pops *knot-edit-description: <project> · <id>* prefilled with the current description'
  done: true
- title: C-c C-c commits via knot update --description, --design, or --body with the buffer contents as one argv element (no shell escaping)
  done: true
- title: C-c C-k discards the buffer without writing
  done: true
- title: Design and body fields use the same capture mode via field-specific keys
  done: true
- title: n in show pops a capture buffer that pipes stdin to knot add-note <id> via call-process-region
  done: true
- title: Capital E in show shells out to knot edit <id> with EDITOR set to emacsclient
  done: true
- title: Successful commits auto-refresh the show buffer
  done: true
deps:
- kno-01kreh4n6ryc
---

## Description

Generic `knot-capture` minor mode plus its three concrete uses: long-form field edits (description / design / body), add-note, and the capital-E escape hatch to `knot edit` via emacsclient.

e in show pops a markdown-mode buffer prefilled with the current ## Description; C-c C-c commits via `knot update --description "..."` passing the buffer contents as a single argv element (no shell escaping); C-c C-k cancels. d and b similarly target design and body. n pops a buffer that pipes its content via stdin to `knot add-note <id>` using `call-process-region`.

Capital E shells out to `knot edit <id>` with EDITOR=emacsclient as the escape hatch for arbitrary structural edits. This is the only path that does NOT go through knot-cli-call's --json envelope, because knot edit is intentionally not --json-shaped.

Modules introduced: `knot-capture` (deep — parameterized by target id / field / post-commit callback).

See docs/prd/knot-el.md user stories 16-21, 'Editing model', and docs/adr/0002-knot-el-prefers-update-over-edit.md for why update + capture beats whole-file edit.

## Notes

**2026-05-12T19:55:05.750738599Z**

Slice 5 implementation landed in emacs/knot.el.

Modules added:
- knot-capture (minor mode, parameterized by id/field/callback): buffer-locals knot-capture--{id,field,callback}, knot-capture-mode-map with C-c C-c → knot-capture-commit and C-c C-k → knot-capture-discard.
- knot-capture--field->flag: description→--description, design→--design, body→--body. note commits via knot add-note (stdin), not knot update.
- knot-capture--field->label: drives buffer names (*knot-edit-description: <project> · <id>*, *knot-edit-design: ...*, *knot-edit-body: ...*, *knot-add-note: ...*).
- knot-capture--extract-section: regex-extracts a `## SectionName` subtree from the body field; used to prefill description/design buffers from the parsed show JSON.
- knot-capture--open: erases buffer, inserts prefill, sets default-directory to project-root, switches to markdown-mode + knot-capture-mode, sets header-line hint, pops to buffer.
- knot-capture-commit: dispatches per field — note uses stdin via knot-cli-call's second arg (call-process-region); description/design/body pass content as a single argv element to knot update --flag. quit-window + kill-buffer + callback.

Show-buffer entry points (added between the existing show section and the update transient):
- e / d / b → knot-show-edit-{description,design,body} via knot-show--open-capture (which captures the originating show buffer and wraps it in knot-capture--make-refresh-callback).
- n → knot-show-add-note (rebound from forward-button; TAB/backtab still cover button nav).
- E → knot-show-edit-via-emacsclient: requires (server-running-p), then async make-process knot edit <id> with process-environment binding EDITOR=knot-emacsclient-executable and VISUAL=…; sentinel refreshes origin show buffer on exit.

defcustom knot-emacsclient-executable "emacsclient" added near the top so users can point at a specific emacsclient binary.

Auto-refresh: knot-capture--make-refresh-callback returns a closure over the originating show buffer; knot-capture-commit invokes it after a successful CLI call, so the show buffer re-fetches and re-renders. Cross-buffer (refreshing the list buffer too) still lands in slice 8.

Verification:
- bb lint:elisp: byte-compile clean. Only pre-existing markdown-mode Package-Requires stale-warning (unchanged since slice 3).
- emacs --batch smoke: all new symbols bound, knot-capture--field->flag mapping correct, knot-capture--extract-section round-trips on a body with Description / Design / Notes sections (and returns "" for missing sections), knot-capture--buffer-name renders the AC-spec'd "*knot-edit-description: <project> · <id>*" shape, show-mode-map binds e/d/b/n/E to the new commands, capture-mode-map binds C-c C-c / C-c C-k.
- End-to-end against a real ticket in a temp project: commit-paths for description (multi-line content), design, and add-note (stdin) all land in the body in the right sections and round-trip back through knot-capture--extract-section.

User requested skipping tests for this slice, so no new entries under test/.

**2026-05-12T19:55:05.833790136Z**

Slice 5 shipped at emacs/knot.el. knot-capture minor mode (buffer-locals: id/field/callback) parameterizes four capture buffers: e/d/b open *knot-edit-{description,design,body}: <project> · <id>* prefilled via knot-capture--extract-section over knot show --json's body field, C-c C-c commits via knot update --description/--design/--body with content as one argv element (no shell escaping); n opens *knot-add-note: ...* and pipes stdin via knot-cli-call's second arg to knot add-note <id>; C-c C-k discards. Capital E shells out async to knot edit <id> with process-environment binding EDITOR=emacsclient (requires server-running-p), sentinel refreshes the show buffer on exit. Successful commits invoke a closure over the originating show buffer that calls knot-show--refresh.
