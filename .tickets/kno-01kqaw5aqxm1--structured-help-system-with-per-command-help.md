---
id: kno-01kqaw5aqxm1
status: open
type: feature
priority: 2
mode: hitl
created: '2026-04-28T20:23:18.013529700Z'
updated: '2026-04-28T20:23:18.013529700Z'
---

# Structured help system with per-command --help support

## Description

The CLI has no --help flag at root or for any subcommand. bb knot --help falls through the case in -main and exits 1 with 'unknown command: --help' plus the bare usage on stderr. Bare bb knot prints usage to stderr and exits 1. There is no per-command help, no flag-level documentation, no usage examples, no exit-code documentation, and no structural grouping in the existing flat usage block.

## Design

Data-driven approach: a single commands registry maps cmd-name to {:group :synopsis :flags :examples :exit-codes}. Both the top-level usage renderer and the per-command help renderer consume that registry, so there is no duplicated text between the two surfaces.

Top-level help groups commands: Project (init, prime), Lifecycle (create, start, status, close, reopen), Graph (dep, undep, link, unlink), Listing (ls, show, ready, blocked, closed), Notes (add-note, edit).

Per-command help renders: synopsis line, flag table with descriptions, at least one example invocation, and exit-code conventions.

Routing changes in -main:
- 'knot --help' / 'knot -h' / 'knot help'     -> top-level help on STDOUT, exit 0
- 'knot help <cmd>'                            -> per-command help on STDOUT, exit 0
- 'knot <cmd> --help' / 'knot <cmd> -h'        -> per-command help on STDOUT, exit 0
- 'knot help <unknown-cmd>'                    -> stderr error, exit 1
- bare 'knot' (no args)                        -> current behavior (usage on stderr, exit 1) so scripts do not silently no-op

Help is rendered as plain text with no ANSI escapes when stdout is piped, matching the existing 'ls' output discipline.

## Acceptance Criteria

- [ ] knot --help, knot -h, and knot help all exit 0 and print to stdout (not stderr)
- [ ] knot help <cmd> and knot <cmd> --help / -h print per-command help on stdout, exit 0
- [ ] Top-level help groups commands (Project / Lifecycle / Graph / Listing / Notes) rather than a flat list
- [ ] Per-command help shows synopsis, flag list with descriptions, at least one example, and exit-code semantics
- [ ] Bare knot (no args) keeps current behavior: usage on stderr, exit 1
- [ ] Help registry is a single data-driven map; no duplicated text between top-level and per-command output
- [ ] knot help unknown-cmd exits 1 with a clear stderr error
- [ ] Integration tests cover: --help at root, --help on at least two subcommands (create + dep tree), and the unknown-help-target error path
- [ ] Help output contains no ANSI escapes when stdout is piped
