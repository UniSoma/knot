---
id: kno-01m0jkmpnkk4
title: LEV and CPL print computed numbers on closed rows under list --status closed
status: closed
type: bug
priority: 3
mode: afk
created: '2026-08-21T16:49:36.434934994Z'
updated: '2026-08-27T16:53:06.064223033Z'
closed: '2026-08-27T16:53:06.064223033Z'
tags:
- listing
- columns
- graph
acceptance:
- title: A closed row under list --status closed renders - for LEV and CPL, matching LVL
  done: true
- title: A listing under --status open --status closed renders - on closed rows and computed numbers on live rows
  done: true
- title: leverage and coupling are null on closed rows in --json, with a json-contract pin for the list --status closed route; existing live-row and closed-command pins unchanged
  done: true
- title: The LEV/CPL help notes and .claude/skills/knot/references/json-protocol.md state the two metrics are null/- on a closed row, matching level
  done: true
- title: ADR 0018's consequence naming the LEV/CPL divergence carries a note that this ticket resolved it
  done: true
links:
- kno-01m0jbrvd2be
deps:
- kno-01m0zvrrs7ct
---

## What to build

`knot list --status closed` renders LEV and CPL with computed numbers on closed rows while LVL renders `-`. Leverage and coupling are defined over the live-induced subgraph (ADRs 0011, 0012); a closed ticket is not a node of it, so a number there is a category error — reporting LEV 2 on a finished ticket invites reading it as a keystone. ADR 0018 records the divergence and leaves it open.

Bring LEV and CPL into line with LVL: on any row whose ticket is closed, the two cells render `-` and the `--json` fields `leverage` and `coupling` are `null`. Blank per row, not per table — `--status` is repeatable, so a listing can mix live and closed rows, and the live rows keep their computed numbers. The `closed` command is untouched: it still omits the fields entirely (ADR 0011).

This is a `--json` shape change: `leverage` and `coupling` become nullable, exactly as `level` already is. The JSON protocol reference and the json-contract pins move in the same commit — existing pins (integers on live rows, fields absent on `closed`-command rows) still hold; the `list --status closed` route gains its own pin. The help notes for the two columns currently promise "always an integer" and must say the metrics are live-only; the column notes are shared across list/ready/blocked, so one edit covers all three. ADR 0018's consequence that names this wart gets a one-line note that it is resolved.

## Notes

**2026-08-27T16:53:06.064223033Z**

attach-per-row in listing.clj now returns nil for closed rows, so LEV/CPL render - and emit null under list --status closed while live rows in a mixed listing keep computed numbers; closed command unchanged. Help notes for both columns, json-protocol.md, a listing_test mixed-status case, and a json-contract pin for list --status closed --json cover it; ADR 0018's consequence notes the resolution.
