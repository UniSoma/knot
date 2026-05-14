---
id: kno-01krj03svqgc
title: Open-children gate at start transitions
status: open
type: task
priority: 2
mode: afk
created: '2026-05-14T01:02:45.102158535Z'
updated: '2026-05-14T01:02:45.102158535Z'
parent: kno-01krhwcy0zdy
tags:
- v0.5
- parent-children-gate
acceptance:
- title: Open-children gate fires on *→active transitions when at least one child is non-terminal
  done: false
- title: '`knot start <parent-with-open-children>` exits non-zero with stderr enumerating the open child ids'
  done: false
- title: '`knot start <parent-with-open-children> --force` succeeds (no `--summary` required)'
  done: false
- title: '`knot update <parent-with-open-children> --status in_progress` fires the same gate (parity with `start`)'
  done: false
- title: '`knot start --json` without `--force` returns `{ok: false, error: "open-children", open-children: [...]}`'
  done: false
- title: Starting a parent whose only children are already terminal succeeds without the gate firing
  done: false
- title: Starting a parent with zero children (no `:parent` pointing at it) succeeds without the gate firing
  done: false
- title: .claude/skills/knot/SKILL.md updated if the start-side error shape or `--force` semantics differ from the close-side path in any user-visible way
  done: false
- title: bb test passes; clj-kondo --lint src test passes
  done: false
deps:
- kno-01krj03bhrxf
---

## Description

## What to build

Extend the open-children gate introduced by kno-01krj03bhrxf to also fire on `* → active-status` transitions (the start path). The gate fires when the target status is the project's `:active-status` and the ticket has at least one child whose status is non-terminal — regardless of what the source status was. "Children" means tickets whose `:parent` equals this ticket's id.

Wire the start-side check into both `status-cmd` (used by `knot start`) and `update-cmd` (used by `knot update --status <active>`), so the gate is keyed on the **transition**, not the command. The override is the existing `--force` flag.

Asymmetry with the close-side gate: forcing past open children at start does **not** require any recorded reason (no `--summary`, no auto-appended note). Per ADR 0003, start is provisional — the maintainer can `update --status` back to intake at zero cost — so the ceremony budget should be proportional to the durability of the action. The stderr enumeration of open child ids at the moment of bypass is the only trace.

JSON envelope on gate firing without `--force`:

    {ok: false, error: "open-children", open-children: [<id> <id> ...]}

Same shape as the close-side firing introduced by kno-01krj03bhrxf, so consumers (the Emacs mode, AFK agents) parse a single envelope across both transitions.

Update `.claude/skills/knot/SKILL.md` and `docs/agents/issue-tracker.md` only if the start-side firing has any user-visible difference from the close-side firing (e.g. mentioning that `--force` at start does not require `--summary`).

Background: see `docs/adr/0003-parent-children-gate-status-transitions-not-readiness.md`.

## Acceptance criteria
