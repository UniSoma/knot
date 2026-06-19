---
id: kno-01kvh277gpsn
title: knot.el — --closure filter seeded from cursor/marks in the list filter transient
status: closed
type: feature
priority: 3
mode: afk
created: '2026-06-19T23:07:25.590203971Z'
updated: '2026-06-19T23:18:56.318412787Z'
closed: '2026-06-19T23:18:56.318412787Z'
tags:
- emacs
acceptance:
- title: Filter transient (f) gains a c suffix that filters the active view to the closure of the current marks-or-cursor seeds; any ticket is a valid seed
  done: true
- title: C-u c clears only the closure filter, leaving other active filters intact
  done: true
- title: Filter transient clear-all rebound from C to R (label still "clear all")
  done: true
- title: Closure composes with other filters, works across list/ready/blocked/closed, and shows in the header-line as closure=<seeds>
  done: true
- title: --via is not surfaced in v1 (closure uses the CLI default of all axes)
  done: true
- title: bb lint:elisp passes; emacs/README.md documents the new c filter and the R clear key
  done: true
---

## Description

Surface the CLI's `--closure` listing filter in the Emacs client as a new dimension of the list filter transient (`f`). Invoking it scopes the active list view to the undirected transitive **closure** of one or more seed tickets over parent/deps/links — "everything related to these tickets."

Seeds are read from buffer context, never typed: the marked tickets if any (display order), otherwise the ticket under point (in a show buffer, the shown ticket) — the same marks-or-cursor rule every bulk operation already uses. Any ticket is a valid seed; no umbrella or type restriction.

v1 is seeds-only. The CLI's `--via` axis-narrowing modifier is intentionally out of scope — the filter always uses the CLI default (all three axes). Directed/depth/axis variants remain additive follow-ups.

Behaviour:

- A new `c` suffix in the filter transient sets the closure filter to the current marks-or-cursor seeds and re-renders.
- `C-u c` clears only the closure filter — there is no prompt, so the usual empty-to-clear path does not apply; this mirrors the prefix-arg convention already used in this transient.
- Closure is one ordinary entry in the buffer-local filter state: it composes (intersects) with every other active filter, applies to all four list views, renders in the header-line verbatim as `closure=<seeds>`, and is cleared by clear-all with the rest.

Folded-in keybinding change: the filter transient's clear-all moves from `C` to `R` (matching the sort transient's `R` reset), so the freed `c` reads as closure rather than colliding with "clear." Label stays "clear all."

Errors need no special handling — seeds are always real, fully-resolved ids from rendered rows, so the CLI never sees an ambiguous/unresolvable seed; the existing CLI-call error path covers the rare delete-out-from-under race.

**Blocked by:** None — can start immediately. The CLI `--closure`/`--via` surface shipped in `2e71984` (kno-01kvgxx219vj).

## Notes

**2026-06-19T23:18:56.318412787Z**

knot.el filter transient gains a c suffix scoping the active view to the undirected closure of the marks-or-cursor seeds (any ticket valid; no prompt). closure is one ordinary filter entry: maps to --closure, composes/intersects with every other filter across list/ready/blocked/closed, renders header-line closure=<seeds>, cleared by clear-all. C-u c clears only closure. Filter clear-all rebound C->R (matching sort reset). --via out of scope (CLI default all axes). README documents c and R.
