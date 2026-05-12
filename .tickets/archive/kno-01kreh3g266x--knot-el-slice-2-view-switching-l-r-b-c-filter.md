---
id: kno-01kreh3g266x
title: 'knot.el slice 2: view switching (l/r/b/c) + filter transient + manual refresh'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:42:43.134919888Z'
updated: '2026-05-12T18:24:58.975353603Z'
closed: '2026-05-12T18:24:58.975353603Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: l/r/b/c switch the view in place; no new buffer is created
  done: true
- title: Header line shows the active view name and any active filter flags
  done: true
- title: A filter transient applies --mode, --tag, --type, --status, --assignee, --limit, --acceptance-complete where the target view accepts them
  done: true
- title: Active filters survive view switching where the target view accepts them; filters incompatible with a view degrade gracefully
  done: true
- title: g re-fetches and re-renders without losing point row or active filters
  done: true
deps:
- kno-01kreh377wy0
---

## Description

Extend the list buffer landed in slice 1 with in-place view switching, filtering, and manual refresh. The same project-scoped *knot-list: <project>* buffer flips between list/ready/blocked/closed with l/r/b/c — no new buffer is created — with the active view and any active filter flags shown in the header line. A filter transient layered on the list view accepts the same vocabulary as the CLI's list/ready/blocked/closed commands. g forces a re-fetch.

Single-buffer-with-view-switching is a deliberate PRD choice (user story 5): four near-identical buffers per project is friction we want to avoid.

See docs/prd/knot-el.md user stories 5-7 and 40, and 'Buffer architecture' for context.

## Notes

**2026-05-12T18:24:58.975353603Z**

Slice 2 shipped at emacs/knot.el. One project-scoped *knot-list: <project>* buffer now flips between list/ready/blocked/closed in place via l/r/b/c (knot-list-view-{list,ready,blocked,closed}); the slice-1 stubs for knot-ready/knot-blocked/knot-closed are now real entry points reusing the same buffer. Buffer-local knot-list--view + knot-list--filters drive a shared knot-list--render path used by view-switching, filter-apply, and refresh; knot-list--build-args + knot-list--view-accepts-p form the per-view accept-mask hook (identity for slice 2 — all four CLI commands accept the same 7 flags — in place for future divergence). Filter transient on f covers --mode/--type/--status/--tag/--assignee/--limit/--acceptance-complete with knot-info-allowed-values-backed completion for enum flags; F clears all. Header-line renders [view] flag=v ... or [view] (no filters); column headers moved into the buffer body via tabulated-list-use-header-line nil. knot-refresh delegates to knot-list--render, saving tabulated-list-get-id pre-print and restoring point onto the same row id post-print. 31/31 runtime smoke pass; bb test 347/0; bb lint:elisp clean (only the known-stale package-lint warning). No CLI / JSON contract changes — bundled knot skill unaffected. Slices 3 / 4 unblocked.
