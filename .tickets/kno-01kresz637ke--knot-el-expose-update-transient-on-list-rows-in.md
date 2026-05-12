---
id: kno-01kresz637ke
title: 'knot.el: expose update transient on list rows ('','' in list)'
status: open
type: feature
priority: 3
mode: hitl
created: '2026-05-12T19:17:39.039590208Z'
updated: '2026-05-12T19:17:39.039590208Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: ''','' on a list row opens the same update transient as in show'
  done: false
- title: Suffixes invoke the same knot-update-set-* setters (status, priority, mode, type, tags, assignee, parent)
  done: false
- title: Each commit is exactly one knot update --flag value subprocess (atomic — no batching)
  done: false
- title: After a successful update, the list buffer re-renders preserving point on the originating row id
  done: false
- title: ok:false envelopes raise user-error in the minibuffer; list buffer state is unchanged on failure
  done: false
- title: Works without a live show buffer for that id (no coupling to knot-show--id / knot-show--data)
  done: false
deps:
- kno-01kreh4yap1c
---

## Description

Slice 4 (kno-01kreh4yap1c) put the atomic frontmatter update transient at `,` in show buffers only. Per the originating PRD story ('bumping priority' without context-switching) and parity with slice 6's start/close and slice 7's deps/links — both of which work on list rows and show — the frontmatter transient should reach list rows too.

Currently `knot-update--ticket-id` requires `knot-show-mode` and reads field values from buffer-local `knot-show--data`. To support list rows the resolver needs to be source-aware:

- In `knot-show-mode`: id from `knot-show--id`, defaults from `knot-show--data`.
- In `knot-list-mode`: id from `tabulated-list-get-id`, defaults from the parsed CLI row (or a one-shot `knot show --json` lookup, since the list row may not carry every field — tags, parent, assignee).

Refresh dispatch on success: `knot-show--refresh` in show, `knot-list--render` in list. (Slice 8's cross-buffer refresh walk will subsume both call sites eventually.)

Suggested shape:

- Factor `knot-update--commit` so the refresh callback is the caller's choice.
- Add `knot-update-from-list` transient (identical suffix layout to `knot-update-from-show`).
- Bind `,` in `knot-list-mode-map`.

Out of scope: status transitions on list rows (start/close land in slice 6 with their own `s` / `x` bindings).
