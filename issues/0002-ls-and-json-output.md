---
id: issue-0002
title: ls command, --json output, and color/TTY discipline
status: ready
type: afk
blocked_by:
  - issue-0001
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0002-ls-and-json-output.md
---

# ls command, --json output, and color/TTY discipline

## Parent document

`docs/prd/knot-v0.md`

## What to build

`knot ls` listing tickets as a table, `--json` output on every read command introduced so far (`show`, `ls`), ANSI color with TTY auto-detection plus `NO_COLOR` and `--no-color` overrides, and the stdout=data / stderr=warnings discipline made explicit and enforced. This slice establishes the output contract that the rest of the read commands will follow.

## User stories covered

- 7 (`--json` output on every read command)
- 8 (snake_case keys in JSON output)
- 36 (ANSI color auto-disabled when piped or `NO_COLOR=1`)
- 37 (stdout = data, stderr = warnings/errors)
- 38 (data layer cleanly separated from CLI — established by routing all rendering through `knot.output`)

## Acceptance criteria

- [ ] `knot ls` renders a table with columns `ID  STATUS  PRI  MODE  TYPE  ASSIGNEE  TITLE` (PRI right-aligned, TITLE truncated on TTY and full-width when piped)
- [ ] Default `ls` filters out terminal-status tickets (the natural backlog view)
- [ ] `knot.output` exposes a TTY/color helper; respects `NO_COLOR`, `--no-color`, and pipe detection
- [ ] Status colored cyan/yellow/dim; priority 0 red+bold; mode and type as faint badges
- [ ] `--json` flag on `show` emits a bare object; `--json` on `ls` emits a bare array (no envelope wrapping)
- [ ] JSON output uses snake_case keys (e.g. `external_refs`)
- [ ] All warnings, info, errors go to stderr; data goes to stdout — verified by integration test piping each command through `>/dev/null`
- [ ] Tests: rendering snapshots for table + JSON shapes, color suppression under `NO_COLOR` and pipe, snake_case key conversion

## Blocked by

- issue-0001 (`issues/0001-foundation-create-and-show.md`)
