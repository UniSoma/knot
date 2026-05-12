---
id: kno-01kreh4yap1c
title: 'knot.el slice 4: update transient for atomic frontmatter mutations'
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:30.508447363Z'
updated: '2026-05-12T16:43:30.508447363Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: Pressing , in a show buffer opens a transient with infix args for status, priority, mode, type, tags, assignee, parent
  done: false
- title: Each infix completion reads from knot-info-current's allowed_values / priority_range / defaults
  done: false
- title: Each commit is exactly one knot update --flag value subprocess (atomic — no batching)
  done: false
- title: After a successful update, the show buffer auto-refreshes from knot show --json
  done: false
- title: ok:false envelopes raise user-error in the minibuffer with the envelope's error message; the buffer state is unchanged on failure
  done: false
deps:
- kno-01kreh4n6ryc
---

## Description

Add the update transient invoked with , in a show buffer. The transient exposes infix args for status, priority, mode, type, tags, assignee, and parent. Each set commits as a single `knot update --flag value` call — no buffer pop — and auto-refreshes the show buffer.

Completion sources come from the cached `knot info` envelope (`allowed_values.statuses`, `types`, `modes`, `priority_range`). Mutating from show refreshes the show buffer immediately; cross-buffer refresh (refreshing the visible list buffer too) lands in slice 8.

Modules introduced: `knot-update` (shallow — per-field transients).

See docs/prd/knot-el.md user story 22, 'Editing model', and 'Refresh model' for the design.
