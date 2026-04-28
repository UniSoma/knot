---
id: kno-01kqa6xfe80k
status: closed
type: task
priority: 2
mode: afk
created: '2026-04-28T14:12:00.584460227Z'
updated: '2026-04-28T19:31:47.670050739Z'
closed: '2026-04-28T19:31:47.670050739Z'
parent: kno-01kqa804gmgx
external_refs:
- docs/prd/knot-v0.md
- issues/0010-prime-and-session-start.md
deps:
- kno-01kqa6xf8rcr
- kno-01kqa6xfd7aw
---

# knot prime and SessionStart hook docs

## Parent document

`docs/prd/knot-v0.md`

## What to build

The `knot prime` command emitting a five-section markdown primer for AI agent context-injection: preamble, project metadata, in-progress tickets, ready tickets (capped at 20 by default), and a schema/command cheatsheet. Flags: `--mode afk` filters the ready section before applying the limit; `--limit N` overrides the cap; `--json` emits the actionable subset (`{project, in_progress, ready, ready_truncated, ready_remaining}`) with snake_case keys. Always exits 0 — including in directories with no Knot project, in projects with zero tickets, and in archive-only projects — so it is safe to wire into a global Claude Code `SessionStart` hook. The README documents the hook setup.

## User stories covered

- 40 (wire `knot prime` into Claude Code `SessionStart` hook)
- 41 (one-line summaries `id  mode  pri  title` for in-progress and ready tickets)
- 42 (`knot prime --mode afk` filters ready section to agent-runnable work)
- 43 (`knot prime --json` emits the actionable ticket subset)
- 44 (`knot prime` always exits 0)

## Acceptance criteria

- [ ] `knot prime` emits five markdown sections to stdout: (1) preamble paragraph, (2) project metadata (prefix, project name if config provides, live/archive counts), (3) in-progress tickets sorted by `:updated` desc, no limit, (4) ready tickets sorted by priority asc then `:created` desc, capped at 20 with truncation footer, (5) schema and command cheatsheet
- [ ] In-progress and ready ticket lines render as `id  mode  pri  title`
- [ ] Truncation footer reads `... +N more (run \`knot ready\`)` when ready exceeds the cap
- [ ] `knot prime --mode afk` filters the ready section to agent-runnable tickets BEFORE applying the limit
- [ ] `knot prime --limit N` overrides the default cap (still mode-filtered first)
- [ ] `knot prime --json` emits a bare object with snake_case keys: `project`, `in_progress`, `ready`, `ready_truncated` (bool), `ready_remaining` (int); drops the preamble, schema, and cheatsheet
- [ ] When run from a directory with no Knot project, the preamble directs the user to `knot init`; exit code is 0
- [ ] When the project has zero tickets, prime emits the preamble + project metadata + empty sections; exit code is 0
- [ ] When the project has only archived tickets, in-progress and ready sections are empty; exit code is 0
- [ ] `knot init` does NOT modify `.claude/settings.json` — hook setup is README-only
- [ ] README includes a `SessionStart` hook setup snippet pointing at `knot prime`
- [ ] Tests in `knot.output` for prime renderers: five-section structure, sort order in each ticket section, truncation footer when ready is capped, `--json` payload shape and key set, exit-0 on missing-project and empty-project states

## Blocked by

- issue-0005 (`issues/0005-dependency-graph.md`)
- issue-0009 (`issues/0009-mode-filters-closed-limit.md`)

## Notes

**2026-04-28T19:11:08.506588504Z**

Implemented knot prime: five-section markdown primer (preamble, project metadata, in-progress, ready capped at 20, schema cheatsheet) plus --mode afk filter (applied before limit), --limit override, and --json bare-object payload with snake_case keys. Always exits 0 across populated/empty/archive-only/no-project states so it is safe to wire into a global Claude Code SessionStart hook. New output/prime-text and output/prime-json renderers are pure (caller controls sort and limit). cli/prime-cmd orchestrates store/load-all + query/ready + query/filter-tickets and dispatches to the renderer. main/prime-handler wraps the call in a try/catch that degrades to a no-project primer rather than exit non-zero. discover-ctx gained :project-found?; knot.config grew an optional :project-name key. README documents the SessionStart hook with matcher=startup; knot init deliberately does not modify .claude/settings.json. Tests added in knot.output-test (renderers), knot.cli-test (orchestrator), and knot.integration-test (exit-0 across all states) — 130 tests / 1075 assertions, all green and stable across multiple runs.
