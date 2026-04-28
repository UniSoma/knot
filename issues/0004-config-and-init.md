---
id: issue-0004
title: .knot.edn config, walk-up discovery, and knot init
status: ready
type: afk
blocked_by:
  - issue-0001
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0004-config-and-init.md
---

# .knot.edn config, walk-up discovery, and knot init

## Parent document

`docs/prd/knot-v0.md`

## What to build

`knot.config` parsing `.knot.edn` at the project root with the full schema, project discovery via walk-up looking for either `.knot.edn` or the configured tickets directory, validation with fail-fast on bad values, and a `knot init` command that writes a self-documenting stub config. This slice replaces the hardcoded defaults from slice 1 with a configurable layer the rest of the system can read from.

## User stories covered

- 22 (configurable status workflow)
- 23 (configurable type list)
- 26 (zero-config — drop into any repo and run; defaults still work without a `.knot.edn`)
- 27 (`knot init` writes a self-documenting stub with all keys present and commented)

## Acceptance criteria

- [ ] `knot.config` parses `.knot.edn` and supports keys: `:tickets-dir`, `:prefix`, `:default-assignee`, `:default-type`, `:default-priority`, `:statuses` (ordered list), `:terminal-statuses` (set), `:types` (list), `:modes` (list), `:default-mode`
- [ ] Defaults match the PRD: `tickets-dir = ".tickets"`, `default-type = "task"`, `default-priority = 2`, `statuses = ["open" "in_progress" "closed"]`, `terminal-statuses = #{"closed"}`, `types = ["bug" "feature" "task" "epic" "chore"]`, `modes = ["afk" "hitl"]`, `default-mode = "hitl"`
- [ ] Project discovery walks up from cwd; first ancestor containing either `.knot.edn` or `<tickets-dir>/` is the project root
- [ ] When both markers exist at different ancestors, the nearest one wins (config wins on conflict)
- [ ] Invalid keys → stderr warning (skip)
- [ ] Invalid values → fail fast at command start with a clear error message
- [ ] `knot init` writes a self-documenting `.knot.edn` stub with all default keys present and inline-commented
- [ ] `knot init` creates `<tickets-dir>` if missing
- [ ] `knot init` flags: `--prefix`, `--tickets-dir`, `--force` (overwrite existing config); without `--force`, aborts on existing config
- [ ] All earlier commands (`create`, `show`, `ls`, `status`, `start`, `close`, `reopen`) now consume config defaults instead of hardcoded values
- [ ] Tests: walk-up discovery (cwd nested several levels deep, both markers at different ancestors, conflicting `:tickets-dir` between config and on-disk directory), default merging when keys are absent, validation errors on bad values

## Blocked by

- issue-0001 (`issues/0001-foundation-create-and-show.md`)
