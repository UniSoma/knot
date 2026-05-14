---
id: kno-01krj03bhrxf
title: Open-children gate at close transitions
status: open
type: task
priority: 1
mode: afk
created: '2026-05-14T01:02:30.455916209Z'
updated: '2026-05-14T02:41:13.411794664Z'
parent: kno-01krhwcy0zdy
tags:
- v0.5
- parent-children-gate
acceptance:
- title: '`gate-open-children!` fires on active→terminal transitions when at least one child is non-terminal, listing open child ids in the thrown ex-info'
  done: false
- title: '`knot close <parent-with-open-children>` exits non-zero with stderr enumerating the open child ids'
  done: false
- title: '`knot close <parent-with-open-children> --force --summary "<reason>"` succeeds; the summary is appended as a note'
  done: false
- title: '`knot update <parent-with-open-children> --status closed` fires the same gate (parity with `close`)'
  done: false
- title: '`knot close --json` without `--force` returns `{ok: false, error: "open-children", open-children: [...]}`'
  done: false
- title: When both AC and open-children gates would fire, a single `--force` bypasses both; stderr lists both classes of bypass
  done: false
- title: Closing a parent whose only children are already terminal succeeds without the gate firing
  done: false
- title: .claude/skills/knot/SKILL.md updated with the new error shape and `--force` override
  done: false
- title: docs/agents/issue-tracker.md updated with the AFK-agent protocol for `ok:false error:\"open-children\"` (read the list, recurse into a child, or pass --force if the umbrella's own work is the close-target)
  done: false
- title: bb test passes; clj-kondo --lint src test passes
  done: false
---

## What to build

Add a new `gate-open-children!` in `src/knot/cli.clj`, structurally parallel to the existing `gate-acceptance!`. The gate fires when the source status equals the project's `:active-status` and the target is in `:terminal-statuses` (i.e. close transitions), and the ticket has at least one child whose status is non-terminal. "Children" means tickets whose `:parent` equals this ticket's id, as already computed by `query/children`.

Wire the gate into both `status-cmd` (the path used by `knot close`) and `update-cmd` (the path used by `knot update --status <terminal>`), so the check is keyed on the **transition**, not the command — mirroring how `gate-acceptance!` already works. The override is the existing `--force` flag; the existing `--force --summary <reason>` requirement on terminal transitions doubles as the recorded reason for bypassing this gate. Stderr enumerates open child ids when the gate fires.

JSON envelope on gate firing without `--force`:

    {ok: false, error: "open-children", open-children: [<id> <id> ...]}

Same envelope shape as the existing AC failure path. When both AC and open-children gates would fire on the same transition, both bypass under a single `--force`; stderr enumerates each so the user sees what got skipped.

Update `.claude/skills/knot/SKILL.md` and `docs/agents/issue-tracker.md` in the same commit, per the AGENTS.md hard rule that the skill must stay in sync with the CLI. Note the new `ok:false error:\"open-children\"` envelope and that the override is the existing `--force` flag.

Background: see `docs/adr/0003-parent-children-gate-status-transitions-not-readiness.md` for the design rationale and rejected alternatives. The core principle: `:parent` is composition (umbrella), `:deps` is sequencing; open children gate status transitions but never block readiness.
