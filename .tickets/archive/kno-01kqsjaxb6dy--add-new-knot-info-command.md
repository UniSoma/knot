---
id: kno-01kqsjaxb6dy
title: Add new `knot info` command
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-05-04T13:20:11.622845040Z'
updated: '2026-05-04T20:46:00.054701611Z'
closed: '2026-05-04T20:46:00.054701611Z'
tags:
- v0.3
---

## Description

Add a new read command, `knot info`, that reports the project's effective runtime configuration and allowed values for agents, scripts, and humans.

This command is for answering questions like:
- what project am I in?
- what paths and defaults is Knot using?
- what statuses/types/modes are valid here?
- what would `knot create` do by default?

`knot info` is a runtime-facts command, not an integrity/debugging command. Leave diagnostics, malformed-ticket reporting, and config/tracker health checks to `knot check`.

## Design

Command surface:
- `knot info`
- `knot info --json`
- `knot info --no-color` (`--no-color` accepted for consistency; text output remains plain)
- no positional args, no filters, no section selectors

Project discovery and failure behavior:
- Match existing Knot discovery semantics exactly: a project is discovered by `.knot.edn` or `.tickets/` at an ancestor.
- If no project is found, fail clearly (do not degrade like `prime`).
- If `.knot.edn` exists but is invalid EDN or fails schema validation, fail completely (no partial payload).
- JSON errors reuse existing codes: `no_project`, `config_invalid`.
- Exit codes stay on the normal 0/1 path (do not adopt `check`'s exit 2 contract).

Ticket-file tolerance and counts:
- `knot info` should be tolerant of malformed ticket files.
- Overall counts should not require parsing tickets.
- `live_count`, `archive_count`, and `total_count` are on-disk markdown-file counts, not parsed-ticket counts.
- Count only top-level `*.md` files in `<tickets-dir>/` and `<tickets-dir>/archive/`; do not recurse.
- Missing configured tickets dirs are valid and yield zero counts.
- Do not surface malformed-ticket warnings/counts in `info`; leave that to `knot check`.

Effective-value policy:
- Prefer effective runtime values throughout.
- Show the effective `prefix` Knot would use for new ids (including derived fallback when config omits `:prefix`).
- Show the effective `tickets_dir` in all cases (including `.tickets` with no config file).
- Preserve the distinction between config and runtime behavior where both matter:
  - `default_assignee` = project config value only (null/(none) when unset)
  - `effective_create_assignee` = what `knot create` would use right now if `--assignee` is omitted (including git fallback)
- Do not derive a `project name`; leave it unset when not configured.
- Apply the same effective-value pattern to similar fields unless a real exception emerges.

Text output:
- Completely plain text by default (no ANSI color).
- Use simple section headings plus `Label: value` lines.
- Always render the full fixed field set.
- Render unset scalar values as `(none)`.
- Render boolean `config_present` as `yes` / `no`.
- Render list fields as compact one-line comma-separated values, preserving order.

Recommended section order and fields:

Project
- Knot version
- Name
- Prefix
- Config present

Paths
- CWD
- Project root
- Config path
- Tickets dir
- Tickets path
- Archive path

Defaults
- Default assignee
- Effective create assignee
- Default type
- Default priority
- Default mode

Allowed Values
- Statuses
- Active status
- Terminal statuses
- Types
- Modes
- Priority range

Counts
- Live count
- Archive count
- Total count

JSON output:
- Use the standard v0.3 envelope on success and error.
- Inside `data`, use a nested payload by section.
- Use snake_case keys everywhere.
- Use stable field presence:
  - absent scalars -> `null`
  - arrays always present, even when empty
  - counts always present as numbers
- Use fixed section/field ordering.
- Inside nested sections, prefer local field names rather than repeating the section name.
- Use `knot_version` (not `version`) to avoid ambiguity with the tracked project.
- Always include `config_path`, `tickets_path`, and `archive_path` as expected effective paths, even when missing on disk.
- Include `config_present`, but do not add a separate discovery-mode field.
- Do not include `cwd_relative_to_project_root`.
- Do not include ID-format metadata beyond the effective `prefix`.

Recommended JSON shape:

```json
{
  "schema_version": 1,
  "ok": true,
  "data": {
    "project": {
      "knot_version": "0.2.0",
      "name": "Knot",
      "prefix": "kno",
      "config_present": true
    },
    "paths": {
      "cwd": "/abs/path/to/cwd",
      "project_root": "/abs/path/to/project",
      "config_path": "/abs/path/to/project/.knot.edn",
      "tickets_dir": ".tickets",
      "tickets_path": "/abs/path/to/project/.tickets",
      "archive_path": "/abs/path/to/project/.tickets/archive"
    },
    "defaults": {
      "default_assignee": null,
      "effective_create_assignee": "Jonas Rodrigues",
      "default_type": "task",
      "default_priority": 2,
      "default_mode": "hitl"
    },
    "allowed_values": {
      "statuses": ["open", "in_progress", "closed"],
      "active_status": "in_progress",
      "terminal_statuses": ["closed"],
      "types": ["bug", "feature", "task", "epic", "chore"],
      "modes": ["afk", "hitl"],
      "priority_range": {"min": 0, "max": 4}
    },
    "counts": {
      "live_count": 25,
      "archive_count": 33,
      "total_count": 58
    }
  }
}
```

Notes on ordering:
- Preserve configured/default order exactly for `statuses`, `types`, and `modes`.
- Normalize `terminal_statuses` to an ordered array by filtering `statuses` in order.

## Acceptance Criteria

- [ ] `knot info` exists as a read command with `--json` and `--no-color`
- [ ] Text output uses plain section headings plus `Label: value` lines with no ANSI color
- [ ] Text output always renders the full fixed field set
- [ ] Unset text scalars render as `(none)`; `config_present` renders as `yes`/`no`
- [ ] JSON output uses the standard v0.3 envelope and a nested `data` payload (`project`, `paths`, `defaults`, `allowed_values`, `counts`)
- [ ] JSON uses snake_case keys, stable field presence, and fixed section/field ordering
- [ ] `project.knot_version`, `project.name`, `project.prefix`, and `project.config_present` are present
- [ ] `paths.cwd`, `paths.project_root`, `paths.config_path`, `paths.tickets_dir`, `paths.tickets_path`, and `paths.archive_path` are present
- [ ] `defaults.default_assignee`, `defaults.effective_create_assignee`, `defaults.default_type`, `defaults.default_priority`, and `defaults.default_mode` are present
- [ ] `allowed_values.statuses`, `allowed_values.active_status`, `allowed_values.terminal_statuses`, `allowed_values.types`, `allowed_values.modes`, and `allowed_values.priority_range.{min,max}` are present
- [ ] `counts.live_count`, `counts.archive_count`, and `counts.total_count` are present
- [ ] `statuses`, `types`, and `modes` preserve configured/default order exactly
- [ ] `terminal_statuses` is emitted as an ordered array in `statuses` order
- [ ] `prefix` is the effective runtime prefix, including the derived fallback when config omits `:prefix`
- [ ] `tickets_dir` is the effective runtime tickets-dir value, including `.tickets` when there is no config file
- [ ] `project.name` stays unset when no project name is configured
- [ ] `default_assignee` remains the config value only; `effective_create_assignee` reflects actual create-time behavior, including git fallback
- [ ] A discovered `.tickets/`-only project with no `.knot.edn` is treated as valid; `config_present` is false and defaults/effective values are still reported
- [ ] A missing configured tickets dir is treated as valid and yields zero counts
- [ ] Counts are on-disk top-level `*.md` file counts in live/archive dirs only; they do not recurse and do not depend on successful ticket parsing
- [ ] Malformed ticket files do not block `knot info` and do not surface diagnostics in its output
- [ ] Running outside a Knot project fails clearly instead of degrading like `prime`
- [ ] Invalid `.knot.edn` fails the command completely (no partial output)
- [ ] `--json` reuses `no_project` and `config_invalid` error codes on those failure paths
- [ ] `knot info` stays on ordinary 0/1 exit codes (no exit 2 contract)
- [ ] Help/docs describe `knot info` as effective runtime configuration / allowed-values reporting, not an integrity/debugging command

## Notes

**2026-05-04T20:46:00.054701611Z**

Implemented `knot info` as a runtime-facts command via TDD vertical slices.

Source (4 files):
- src/knot/cli.clj: info-cmd, info-data, effective-create-assignee, count-md-files (top-level *.md only, no parsing, no recursion)
- src/knot/output.clj: info-text (plain-text renderer with section helpers), info-json (v0.3 envelope)
- src/knot/main.clj: info-handler with strict discovery; emits no_project/config_invalid error envelopes (under --json) or stderr; stays on 0/1 exit codes
- src/knot/help.clj: :info registry entry + slot in command-order between :prime and :check

Tests (+97 assertions, 273 total / 2488 assertions / 0 failures):
- cli_test: shape, JSON envelope, project/paths/defaults/allowed_values/counts blocks, edge cases (missing dirs, non-md files, no recursion, malformed tolerance), assignee precedence (config-only :default_assignee vs runtime :effective_create_assignee with git fallback)
- output_test: info-text rendering — fixed sections, (none) for unset, yes/no for config_present, comma-joined lists, priority_range as min-max, no ANSI
- integration_test: 9 e2e subtests (text + JSON + derived prefix + configured prefix + no-project + invalid .knot.edn + --no-color + malformed counts)
- help_test: :info added to expected-cmd-keys parity set

Docs:
- .claude/skills/knot/SKILL.md: two new intent rows (what project / what does create default to) + info in quick-reference banner
- CHANGELOG.md: feature entry under [Unreleased]

Smoke-tested live: knot info, knot info --json, knot info --help, and from a non-project dir. Lint baseline unchanged (4 errors / 5 warnings, all pre-existing).
