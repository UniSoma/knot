---
id: kno-01krj03svqgc
title: Open-children gate at start transitions
status: closed
type: task
priority: 2
mode: afk
created: '2026-05-14T01:02:45.102158535Z'
updated: '2026-05-15T18:39:49.951221907Z'
closed: '2026-05-15T18:39:49.951221907Z'
parent: kno-01krhwcy0zdy
tags:
- v0.5
- parent-children-gate
acceptance:
- title: Open-children gate fires on *→active transitions when at least one child is non-terminal
  done: true
- title: '`knot start <parent-with-open-children>` exits non-zero with stderr enumerating the open child ids'
  done: true
- title: '`knot start <parent-with-open-children> --force` succeeds (no `--summary` required)'
  done: true
- title: '`knot update <parent-with-open-children> --status in_progress` fires the same gate (parity with `start`)'
  done: true
- title: '`knot start --json` without `--force` returns `{ok: false, error: "open-children", open-children: [...]}`'
  done: true
- title: Starting a parent whose only children are already terminal succeeds without the gate firing
  done: true
- title: Starting a parent with zero children (no `:parent` pointing at it) succeeds without the gate firing
  done: true
- title: .claude/skills/knot/SKILL.md updated if the start-side error shape or `--force` semantics differ from the close-side path in any user-visible way
  done: true
- title: bb test passes; clj-kondo --lint src test passes
  done: true
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

## Notes

**2026-05-15T18:39:49.951221907Z**

Extended gate-open-children! in src/knot/cli.clj to fire on *→active transitions (source≠active, target=active) parallel to the existing active→terminal close-side firing. Ex-info now carries :gate :start|:close so warn-open-children-bypass! and emit-open-children! dispatch the correct stderr footer/warning. --force is asymmetric per ADR-0003: close still requires --summary, start does not (start is provisional). Added --force flag to :start in help.clj and updated --force descriptions on :status / :close / :update. JSON envelope shape unchanged across both gates (open_children + open_children: [<id>...]). Existing close-side tests adjusted to add :force? true on setup start-cmd steps. New tests: 5 cli_test sub-tests (cli_test.clj open-children-gate-at-start-test), one json_contract envelope test, one integration_test for the start stderr footer. 368/368 tests green; clj-kondo baseline unchanged (3 pre-existing macro-noise errors). SKILL.md and docs/agents/issue-tracker.md updated for the two-direction gate + asymmetric --force semantics. knot.el docstrings on knot-start / knot-close updated to mention the open-children gate (no behavior change — knot-cli--parse handles the new envelope generically).
