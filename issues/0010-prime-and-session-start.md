---
id: issue-0010
title: knot prime and SessionStart hook docs
status: done
type: afk
blocked_by:
  - issue-0005
  - issue-0009
parent: docs/prd/knot-v0.md
created: 2026-04-28
completed: 2026-04-28
filename: issues/0010-prime-and-session-start.md
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

- [x] `knot prime` emits five markdown sections to stdout: (1) preamble paragraph, (2) project metadata (prefix, project name if config provides, live/archive counts), (3) in-progress tickets sorted by `:updated` desc, no limit, (4) ready tickets sorted by priority asc then `:created` desc, capped at 20 with truncation footer, (5) schema and command cheatsheet
- [x] In-progress and ready ticket lines render as `id  mode  pri  title`
- [x] Truncation footer reads `... +N more (run \`knot ready\`)` when ready exceeds the cap
- [x] `knot prime --mode afk` filters the ready section to agent-runnable tickets BEFORE applying the limit
- [x] `knot prime --limit N` overrides the default cap (still mode-filtered first)
- [x] `knot prime --json` emits a bare object with snake_case keys: `project`, `in_progress`, `ready`, `ready_truncated` (bool), `ready_remaining` (int); drops the preamble, schema, and cheatsheet
- [x] When run from a directory with no Knot project, the preamble directs the user to `knot init`; exit code is 0
- [x] When the project has zero tickets, prime emits the preamble + project metadata + empty sections; exit code is 0
- [x] When the project has only archived tickets, in-progress and ready sections are empty; exit code is 0
- [x] `knot init` does NOT modify `.claude/settings.json` — hook setup is README-only
- [x] README includes a `SessionStart` hook setup snippet pointing at `knot prime`
- [x] Tests in `knot.output` for prime renderers: five-section structure, sort order in each ticket section, truncation footer when ready is capped, `--json` payload shape and key set, exit-0 on missing-project and empty-project states

## Blocked by

- issue-0005 (`issues/0005-dependency-graph.md`)
- issue-0009 (`issues/0009-mode-filters-closed-limit.md`)

## Implementation notes

- Two new pure renderers in `knot.output`: `prime-text` for the five-section markdown primer and `prime-json` for the bare-object actionable subset. Renderers do not sort or truncate — the orchestrator hands them already-sorted, already-limited tickets so render-vs-policy stays cleanly separated.
- New `knot.cli/prime-cmd` orchestrates: loads tickets via `store/load-all`, partitions in-progress (status `in_progress`, sorted by `:updated` desc) from ready (`query/ready` then `query/filter-tickets` for mode), applies the cap (default 20) AFTER mode filtering, computes `ready-truncated?` / `ready-remaining`, and dispatches to the text or JSON renderer based on `:json?`. Always returns a string — never throws, never returns nil — so the SessionStart hook can never break the agent's session.
- New `:project-found?` flag added to `discover-ctx` in `knot.main` so `prime-cmd` can render a "run `knot init`" preamble in directories with no Knot project rather than emit a misleading primer rooted at cwd.
- New optional `:project-name` config key (added to `:known-keys` in `knot.config`). Renderer omits the `name:` line in `## Project` when the key is absent — distinguishes "no name" from "empty name."
- `prime-handler` in `knot.main` wraps `cli/prime-cmd` in a try/catch that degrades to a no-project primer on any error so argument-parser failures still exit 0 (load-bearing for global SessionStart wiring).
- Default ready cap (`prime-default-limit = 20`) is a private constant in `knot.cli`. `--limit N` overrides it; mode filtering always runs first, so `--mode afk --limit 5` returns up to five afk tickets, not five from the unfiltered set. Locked in by a 3-hitl + 4-afk integration test that would fail under naive "limit then filter" code.
- Truncation footer renders as `... +N more (run \`knot ready\`)` and only appears when the post-filter ready count exceeds the cap.
- JSON payload uses snake_case at the JSON layer (`in_progress`, `ready_truncated`, `ready_remaining`, `live_count`, `archive_count`) while the internal Clojure keys stay kebab-case — projection happens in `jsonify-prime-project` / `jsonify-prime-ticket`.
- Tests added across three layers: pure renderer tests in `knot.output-test` (sections, ticket-line format, truncation footer, JSON shape and keys, no-project preamble); orchestrator tests in `knot.cli-test` (sort orders, mode filter, default cap, limit override, archive-only, empty project, no-project, JSON branch, project-name pass-through); end-to-end integration tests in `knot.integration-test` (exit-0 across populated/empty/archive-only/no-project, `--json` shape, `--mode afk` and `--limit` flags, and a regression test asserting `knot init` does not touch `.claude/settings.json`).
- High-volume create loops needed `Thread/sleep 2` between calls (`create-spaced!` helper in `cli_test`) to avoid the ~1/1024 same-millisecond ID collision rate from `random-suffix`'s 2-char Crockford-base32 random tail. The 22-ticket cap test had ~22% flake odds without it.
- README documents the Claude Code `SessionStart` hook setup with the canonical `matcher: "startup"` shape. Plain stdout from `knot prime` is injected as additional context — no JSON wrapper required. `knot init` deliberately does NOT modify `.claude/settings.json`; hook setup is opt-in, never automatic.
