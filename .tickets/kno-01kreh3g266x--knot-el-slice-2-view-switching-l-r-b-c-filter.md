---
id: kno-01kreh3g266x
title: 'knot.el slice 2: view switching (l/r/b/c) + filter transient + manual refresh'
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:42:43.134919888Z'
updated: '2026-05-12T16:42:43.134919888Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: l/r/b/c switch the view in place; no new buffer is created
  done: false
- title: Header line shows the active view name and any active filter flags
  done: false
- title: A filter transient applies --mode, --tag, --type, --status, --assignee, --limit, --acceptance-complete where the target view accepts them
  done: false
- title: Active filters survive view switching where the target view accepts them; filters incompatible with a view degrade gracefully
  done: false
- title: g re-fetches and re-renders without losing point row or active filters
  done: false
deps:
- kno-01kreh377wy0
---

## Description

Extend the list buffer landed in slice 1 with in-place view switching, filtering, and manual refresh. The same project-scoped *knot-list: <project>* buffer flips between list/ready/blocked/closed with l/r/b/c — no new buffer is created — with the active view and any active filter flags shown in the header line. A filter transient layered on the list view accepts the same vocabulary as the CLI's list/ready/blocked/closed commands. g forces a re-fetch.

Single-buffer-with-view-switching is a deliberate PRD choice (user story 5): four near-identical buffers per project is friction we want to avoid.

See docs/prd/knot-el.md user stories 5-7 and 40, and 'Buffer architecture' for context.
