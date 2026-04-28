---
id: issue-0002
title: ls command, --json output, and color/TTY discipline
status: done
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

- [x] `knot ls` renders a table with columns `ID  STATUS  PRI  MODE  TYPE  ASSIGNEE  TITLE` (PRI right-aligned, TITLE truncated on TTY and full-width when piped)
- [x] Default `ls` filters out terminal-status tickets (the natural backlog view)
- [x] `knot.output` exposes a TTY/color helper; respects `NO_COLOR`, `--no-color`, and pipe detection
- [x] Status colored cyan/yellow/dim; priority 0 red+bold; mode and type as faint badges
- [x] `--json` flag on `show` emits a bare object; `--json` on `ls` emits a bare array (no envelope wrapping)
- [x] JSON output uses snake_case keys (e.g. `external_refs`)
- [x] All warnings, info, errors go to stderr; data goes to stdout — verified by integration test piping each command through `>/dev/null`
- [x] Tests: rendering snapshots for table + JSON shapes, color suppression under `NO_COLOR` and pipe, snake_case key conversion

## Blocked by

- issue-0001 (`issues/0001-foundation-create-and-show.md`)

## Implementation notes

- **`show --json` shape**: frontmatter fields are flattened to the top level of the object, plus a `body` field carrying the raw markdown. The PRD said "bare object" but did not specify the shape; this is the chosen contract.
- **`ls --json` omits body**: list view stays compact. Consumers that need a body re-fetch via `show --json`.
- **TITLE source**: there is no `:title` frontmatter field. The `ls` table extracts the first `# …` H1 line from the body. Editing the H1 by hand reflows into `ls` on the next read with no resave.
- **Title width is honored strictly**: when the non-title prefix already meets or exceeds the requested width, the TITLE column collapses to 0 chars. No minimum-floor; `:width` wins so callers can rely on the row never exceeding what they asked for.
- **`load-all` sort order**: by filename, which (because IDs are timestamp-prefixed Crockford-base32) sorts by creation time ascending. Default `ls` is therefore oldest-first.
- **NO_COLOR semantics**: per [no-color.org](https://no-color.org/), `NO_COLOR=""` does *not* disable color — only non-empty values do. This is asserted in `output_test`.
- **stdout newline rule**: `knot.main/println-out` adds a trailing newline only when the rendered output does not already end in `\n`. This preserves `slurp(file) == stdout` byte-equality for `show` (the YAML render already ends in `\n\n`) while keeping `ls` and `--json` output terminating on a fresh line for interactive use.
