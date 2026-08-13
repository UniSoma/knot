---
id: kno-01kzyefq5cxv
title: 'Rebalance the three context surfaces: pull, push, pointer'
status: closed
type: chore
priority: 2
mode: hitl
created: '2026-08-13T20:54:41.580577476Z'
updated: '2026-08-13T21:02:05.353100033Z'
closed: '2026-08-13T21:02:05.353100033Z'
acceptance:
- title: 'push: hitl preamble keeps only 3 intent rows (ready, show, close --summary), chosen by irrecoverability'
  done: true
- title: 'push: Recently Closed drops summaries, keeps id + title one-liners'
  done: true
- title: 'push: prime-skill-pointer deleted and both sentences inlined; the knot edit TTY parenthetical leaves the afk preamble'
  done: true
- title: 'pull: knot help edit carries the no-TTY note'
  done: true
- title: 'pointer: Command index deleted, gotcha paragraph kept'
  done: true
- title: 'pointer: autonomous loop reduced to the mode-as-contract paragraph plus one line pointing at knot prime --mode afk'
  done: true
- title: 'pointer: skill stops restating the route-through-the-CLI rule, keeps the why'
  done: true
- title: prime and skill description keep disjoint branch lists
  done: true
- title: ADR 0017 written; AGENTS.md sync rule replaced by the broader one; CONTEXT.md records the leverage collision
  done: true
---

## Description

knot reaches a client project through three context surfaces, distinguished by when material enters the agent's context:

- pull — `knot --help` / `knot help <cmd>`. Zero context cost, fetched on demand, generated from code, cannot drift.
- push — `knot prime` via the SessionStart hook. Always loaded, every turn, in every project.
- pointer — the bundled `knot` skill. Description always loaded; body loaded when it fires.

Material currently sits on the wrong surfaces: the intent table is duplicated across push and pointer, the skill caches flag inventories that pull generates, and Recently Closed spends 38% of push's words on mid-sentence-truncated summaries.

Rebalance so each piece of material lives on exactly one surface. See ADR 0017.

## Design

Placement rule (ADR 0017): pull is authoritative for anything derivable from the CLI; push carries only what a cold agent cannot recover plus live state; pointer carries judgment the help text cannot.

Failure ranking that drives it: (a) agent hand-edits/greps .tickets/ and corrupts the corpus > (d) agent never reaches the skill > (b) agent picks the wrong command/flag > (c) agent burns context. Only (a) is unrecoverable; (b) is self-correcting because unknown flags are rejected.

## Notes

**2026-08-13T21:02:05.353100033Z**

All three surfaces rebalanced per ADR 0017. Push (knot prime) drops 438→226 words: three intent rows chosen by irrecoverability, Recently Closed down to id+title, prime-skill-pointer deleted. Pull gains a NOTES section, first used for knot edit's no-TTY caveat. Pointer drops its command index, its duplicate autonomous loop, and its restatement of the routing rule. AGENTS.md's skill-sync rule widened to all three surfaces; CONTEXT.md records that 'leverage' stays the ticket metric.
