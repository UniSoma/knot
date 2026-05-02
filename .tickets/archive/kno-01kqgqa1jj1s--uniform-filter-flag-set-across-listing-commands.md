---
id: kno-01kqgqa1jj1s
title: Uniform filter flag set across listing commands and prime
status: closed
type: task
priority: 2
mode: afk
created: '2026-05-01T02:53:56.178330647Z'
updated: '2026-05-02T20:17:21.273320815Z'
closed: '2026-05-02T20:17:21.273320815Z'
tags:
- v0.3
- cli
- needs-triage
links:
- kno-01kqe9ytd40z
---

## Description

Make the listing-command filter set uniform across `list`, `ready`, `blocked`, `closed`, and `prime`.

Today only `list` and `ready` accept `--status --assignee --tag --type --mode`; `blocked` and `closed` lack them. `--limit` is on `ready/blocked/closed` but not `list`. After this slice every listing command accepts the same six-flag set: `--status --assignee --tag --type --mode --limit` (all repeatable where they take values).

On `prime`, filters apply across **all** selectable sections (in_progress + ready + recently_closed). Partial application (e.g. only filtering `ready`) would surprise — agents reading `prime --assignee me` should see only their tickets across every section.

Empty filter results are valid empty arrays, not errors. No contradictory-filter rejection (e.g. `closed --status open` is allowed and returns `[]`).

## Acceptance Criteria

- [ ] `list` accepts `--limit`
- [ ] `blocked` accepts `--status --assignee --tag --type --mode`
- [ ] `closed` accepts `--status --assignee --tag --type --mode`
- [ ] `prime` accepts `--status --assignee --tag --type` in addition to existing `--mode --limit`
- [ ] Filters on `prime` apply to all sections, not just `ready`
- [ ] Empty result is a valid empty array, not an error
- [ ] Help text reflects the unified flag set on every command
- [ ] Tests cover the new flag combinations on each command

## Notes

**2026-05-02T20:17:21.273320815Z**

Uniform six-flag filter set (--status --assignee --tag --type --mode --limit) across all five listing commands. list gains --limit; blocked and closed gain all five filter flags; prime gains --status --assignee --tag --type with filters applied to in_progress + ready + recently_closed. apply-limit moved before ls-cmd in cli.clj. Rejection test removed; 20 new tests (9 unit, 11 integration). CHANGELOG updated; SKILL.md synced. 261 tests / 2244 assertions / 0 failures; lint baseline unchanged.
