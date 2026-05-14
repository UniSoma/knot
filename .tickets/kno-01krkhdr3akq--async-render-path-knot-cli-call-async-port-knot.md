---
id: kno-01krkhdr3akq
title: 'Async render path: knot-cli-call-async + port knot-list--render'
status: open
type: feature
priority: 4
mode: hitl
created: '2026-05-14T15:24:31.200225717Z'
updated: '2026-05-14T15:24:31.200225717Z'
tags:
- emacs
acceptance:
- title: 'Design decision recorded (commit body or docs/agents/ note): callback signature, JSON accumulation, error propagation, cancellation'
  done: false
- title: knot-cli-call-async implemented with make-process + sentinel + filter
  done: false
- title: knot-list--render uses the async path
  done: false
- title: Header-line shows a loading indicator while CLI runs
  done: false
- title: Superseded renders are cancelled (kill-process)
  done: false
- title: knot-cli-call retained and used for all mutation commands
  done: false
- title: No regression in interactive flows (filter, sort, mark, refresh)
  done: false
- title: bb test passes
  done: false
deps:
- kno-01krkhb71r1p
---

## Description

Currently every `knot-cli-call` invocation blocks the UI on subprocess + JSON parse. On large projects this causes visible freezes during `knot list` renders.

**HITL** because the async API shape (callback signature, error propagation, cancellation semantics, in-flight indicator UX) warrants a design pass before an agent commits to it.

Design constraints:
- New `knot-cli-call-async` lives alongside the existing synchronous `knot-cli-call` — not a replacement. Mutation commands (create, update, close, dep, etc.) stay on the sync path. Read-heavy renders move to async.
- Pattern modelled on existing `knot-show-edit-via-emacsclient` (~lines 2121–2135), which already demonstrates `make-process` + sentinel in this codebase.
- Sentinel accumulates stdout in a process buffer; on `finished\n` exit, parse the JSON and invoke the callback with the result. On non-zero exit, surface the error via the callback's error arm.
- Header-line gains a 'loading…' indicator while a render is in flight. Cancellation: a new render request supersedes any in-flight one (kill-process the old, start the new).

First port: `knot-list--render`. Deps tree port is a separate AFK follow-up.

Design notes belong either in commit body or a short docs/agents/ entry — synthesizer's call, but record the shape so #9 (and any future async port) can follow the same pattern.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Theme 6, Tier 4).
