---
id: kno-01m128tdp7t8
title: Scope --force's --summary demand to a firing gate on every surface
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-27T18:48:20.423365303Z'
updated: '2026-08-27T18:51:05.771989154Z'
closed: '2026-08-27T18:51:05.771989154Z'
acceptance:
- title: The --force flag :desc on close, status, and update in knot.help says --summary is required when a gate fires, not unconditionally
  done: true
- title: The transition-status! docstring in cli.clj states the same condition
  done: true
- title: ADR 0003 carries a dated bracketed amendment noting the demand is scoped to a firing gate
  done: true
- title: CONTEXT.md's Transition gate and Override record entries are committed with the change
  done: true
- title: bb test and clj-kondo pass
  done: true
external_refs:
- git:c169e80
---

## Description

`knot close <id> --force` on a ticket where no transition gate fires closes it summary-less (exit 0): in `cli.clj` the `--force requires a non-blank --summary` check sits after `(not fires?) nil`. Three surfaces state the demand unconditionally — the `--force` flag `:desc` on `close`, `status`, and `update` in `knot.help`; the `transition-status!` docstring in `cli.clj`; and ADR 0003 ("already requires a recorded reason").

Decision (grill 2026-08-27): the summary is the **override record** — the reason a gate was bypassed. A `--force` that bypasses nothing owes nothing, consistent with a plain `knot close` never demanding a summary. Behavior stays; the three surfaces move to match it. `references/lifecycle-gates.md` and the glossary (**Transition gate**, **Override record** in `CONTEXT.md`) already carry the corrected model.

## Notes

**2026-08-27T18:51:05.771989154Z**

Scoped the --force/--summary demand to a firing gate on close, status, and update help :desc, the transition-status! docstring, and ADR 0003 (dated amendment). Glossary gained Transition gate and Override record. Behavior unchanged.
