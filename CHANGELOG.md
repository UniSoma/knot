# Changelog

All notable changes to Knot are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Versioning

- Tags are cut as `vX.Y.Z` (e.g. `v0.0.1`).
- The single source of truth for the current version is `src/knot/version.clj`,
  surfaced to users via `knot --version` and the `knot --help` banner.
- Version bumps are driven by the `/release` slash command.

## [Unreleased]

### Added

- New `knot info` command reports the project's effective runtime
  configuration and allowed values for agents, scripts, and humans.
  Five fixed sections: `Project` (knot version, name, prefix,
  config_present), `Paths` (cwd, project root, config path, tickets
  dir/path, archive path), `Defaults` (config-only `default_assignee`
  vs runtime `effective_create_assignee` with git fallback,
  `default_type`, `default_priority`, `default_mode`), `Allowed
  Values` (statuses, active_status, terminal_statuses ordered by the
  configured statuses order, types, modes, priority_range), and
  `Counts` (live, archive, total — top-level `*.md` files only, no
  parsing). `--json` returns the v0.3 envelope; `--no-color` is
  accepted for consistency (text is always plain). Tolerant of
  malformed ticket files; counts use raw filesystem listing. Strict
  on discovery: missing project or invalid `.knot.edn` exits 1 with
  reusable `no_project` / `config_invalid` error envelopes (under
  `--json`) — `info` is for runtime facts, not health verdicts, so it
  stays on the ordinary 0/1 path. Diagnostics, malformed-ticket
  reporting, and config-health checks remain `knot check`'s job.

- Uniform six-flag filter set across all listing commands. `list` gains
  `--limit`; `blocked` and `closed` gain `--status`, `--assignee`,
  `--tag`, `--type`, `--mode`; `prime` gains `--status`, `--assignee`,
  `--tag`, `--type`. On `prime`, filters apply across **all** sections
  (in_progress + ready + recently_closed) — e.g. `knot prime --assignee
  me` shows only your tickets in every section. Empty filter results are
  valid empty arrays, not errors.

- New `knot update <id>` command for non-interactive ticket writes.
  Frontmatter flags (`--title`, `--type`, `--priority`, `--mode`,
  `--assignee`, `--parent`, `--tags`, `--external-ref`) set field
  values; passing a blank string (or empty repeated `--external-ref`
  list) clears `:assignee` / `:parent` / `:tags` / `:external_refs`.
  Body flags replace named sections in place: `--description`,
  `--design`, `--acceptance`. `--body <text>` replaces the *whole*
  body and is destructive — there is **no `--force` ceremony**; git is
  the documented undo path. `--body` is mutually exclusive with the
  sectional body flags. `--json` returns the v0.3 success envelope
  wrapping the post-mutation ticket under `:data` (no `:meta` slot —
  `update` never archives). `:updated` bumps on every successful save
  via `store/save!`. `--note` is intentionally absent: append remains
  `add-note`'s job, while `update` is purely set/replace. `edit` keeps
  its single meaning (open in `$EDITOR`); `update` is the
  non-interactive path agents and scripts use.

- New `knot check [<id>...]` command validates project integrity and
  surfaces issues. With no ids, scans every ticket (live + archive) and
  config; with ids, narrows the per-ticket tier to those (globals always
  run on the full set). Initial check codes: `dep_cycle`, `unknown_id`
  (dangling `:deps`/`:links`/`:parent`), `invalid_status`,
  `invalid_type`, `invalid_mode`, `invalid_priority` (outside 0..4),
  `terminal_outside_archive` (bidirectional), `missing_required_field`,
  `frontmatter_parse_error`, `invalid_active_status`. Filter flags
  `--severity error|warning` and `--code <code>` are repeatable; OR
  within a flag, AND across flags; unknown severity is rejected at parse
  time, unknown code is silently accepted (open enum). Filters apply
  *before* the exit-code decision (grep semantics: exit reflects the
  filtered view). Exit codes: 0 clean, 1 errors found, 2 unable to scan
  (no project root, invalid `.knot.edn`). Issues sort severity desc →
  code asc → first-id asc → message asc, identical in JSON and text.

### Fixed

- `knot info --bogus --json` (any unknown flag with `--json`) now emits
  the v0.3 `invalid_argument` error envelope on stdout instead of
  printing plain stderr text. Plain `knot info --bogus` (no `--json`)
  still emits the existing `knot info: Unknown option: ...` stderr
  message. Exit code stays at `1` on both paths. Brings `info` in line
  with the rest of the JSON-aware error contract.

- `--json` ticket payloads always include `tags`, `deps`, `links`, and
  `external_refs` as arrays — empty (`[]`) when the ticket has no
  value, populated otherwise. Previously these keys were absent for
  tickets that never set them, breaking `jq` pipelines like
  `knot list --json | jq -r '[.data[].tags[]]'` with `null[]` errors.
  Affects every command whose `--json` payload carries a ticket: read
  side (`list`, `show`) and mutating side
  (`create`/`start`/`status`/`close`/`reopen`/`add-note`/`update`/`dep`/`undep`/`link`/`unlink`).
  On-disk YAML pruning is unchanged: `.md` files for tickets without
  values still omit the field — the default is injected only at the
  JSON boundary.

### Changed (BREAKING)

- `knot create` no longer accepts the `--afk` and `--hitl` shortcut
  flags. `--mode <value>` is the only path to set the mode at create
  time. The shortcuts baked the canonical mode names `"afk"` and
  `"hitl"` into CLI parsing — projects that customize `:modes` (e.g.
  `["solo" "team"]`) would expose shortcuts referencing modes they did
  not have. `:create` is also now a strict-parsing command: unknown
  flags (`--afk`, `--hitl`, `--body`, anything mistyped) exit non-zero
  with `knot: Unknown option: :<name>` on stderr, matching the
  behavior already in place on `prime`/`ready`/`blocked`/`closed`. The
  init stub documents the per-mode-shortcut invariant under `:modes`
  for future contributors. Pre-1.0 break window — no deprecation
  cycle. Migration: replace `--afk` with `--mode afk` and `--hitl`
  with `--mode hitl`.
- `knot dep cycle` is **removed**; its role is subsumed by `knot check
  --code dep_cycle`. The semantic shift: `dep cycle` previously scanned
  only non-terminal tickets, while `knot check` scans the whole project
  (live + archive). Cycles among archived tickets now surface as issues
  — they are real data-integrity problems if a ticket is later reopened.
- The v0.3 envelope contract is **extended**: `knot check --json` is the
  first command where `ok` mirrors a *health verdict*, so `ok: false`
  may now coexist with a `data` slot when errors are present
  (`{schema_version: 1, ok: false, data: {issues: [...], scanned:
  {...}}}`). The earlier rule (`ok: false` ↔ `error` slot, no `data`)
  still holds for the cannot-scan case (exit 2). Argument-parse errors
  for `knot check` stay on stderr with exit 2 in both modes — matches
  the arg-parsing-stays-on-stderr policy.
- All `--json` read commands now wrap their output in a tagged envelope
  `{schema_version: 1, ok: true, data: <payload>}` instead of returning a
  bare object/array. `knot list/ready/blocked/closed --json` change from
  `[ ... ]` to `{"schema_version": 1, "ok": true, "data": [ ... ]}`;
  `knot show --json`, `knot dep tree --json`, and `knot prime --json`
  change from `{ ... }` to `{"schema_version": 1, "ok": true,
  "data": { ... }}`. The `data` payload is unchanged from prior shapes.

### Added

- Error path for `--json` read commands now emits a structured error
  envelope on stdout with exit code 1 instead of a stderr message:
  `{"schema_version": 1, "ok": false, "error": {"code": "...",
  "message": "...", "candidates"?: [...]}}`. `knot show <missing>
  --json` carries `code: "not_found"`; partial-id ambiguity on `knot
  show --json` and `knot dep tree --json` carries `code:
  "ambiguous_id"` with a `candidates` array.
- `knot dep tree <unknown-id> --json` intentionally returns a *success*
  envelope with `data.missing: true` rather than a `not_found` error —
  dep tree is tolerant of missing roots so consumers can discover broken
  `:deps` refs *via* the parent that links to them. JSON consumers
  should branch on `data.missing` distinctly from `ok: false`.
- Argument-parsing failures (e.g. `--limit 0`, missing required
  positional args) continue to die on stderr with exit 1 — these are
  CLI-usage errors, not data conditions, and stay outside the JSON
  envelope contract.
- `--json` flag now extends to every mutating command:
  `create`, `start`, `status`, `close`, `reopen`, `dep`, `undep`,
  `link`, `unlink`, `add-note`. Eliminates the read-after-write
  round-trip for agents — the envelope's `data` is the touched ticket.
  Lifecycle commands and `add-note` emit the post-mutation ticket
  (single object, body included). `dep`/`undep` emit the `from` ticket
  with the updated `:deps`. `link`/`unlink` emit an array of every
  touched ticket (body excluded, ls-shape). `init` and `edit` are
  excluded — `init` is project setup (no ticket), `edit` opens
  `$EDITOR` (interactive only).
- `close --json` and `status <id> <terminal-status> --json` populate a
  top-level `:meta {:archived_to <path>}` slot in the envelope so
  callers do not have to infer archive routing. The envelope grows
  one slot: `{schema_version, ok, data, meta}` — `:meta` is omitted
  when none applies (every non-terminal mutation).
- Error envelopes extend the read-side contract to writes: missing
  ids emit `{ok:false, error:{code:"not_found", message}}` on stdout
  (exit 1). Partial-id ambiguity emits `code: "ambiguous_id"` with a
  `candidates` array. `dep --json` cycle rejection emits `code:
  "cycle"` with the offending path under `error.cycle`.

## [0.2.0] - 2026-04-30

### Added

- `knot prime` emits a new `## Recently Closed` section showing the last 3
  closed tickets with their close `--summary` (extracted as the most recent
  `## Notes` block from the ticket body).
- `knot prime --mode afk` swaps the human-oriented preamble for an
  autonomous-agent flow checklist (claim → work → add-note → close).
- In-progress tickets older than 14 days are flagged with a `[stale]` prefix
  in the text primer and a `"stale": true` field on JSON `in_progress`
  entries.
- `knot prime --json` output gains a top-level `recently_closed` array.
  JSON consumers should tolerate unknown keys; new fields may be added in
  future minor versions.
- `knot.ticket/latest-note-content` extracts the most recent timestamped
  note from a ticket body (used by Recently Closed).
- A bundled `knot` skill at `.claude/skills/knot/SKILL.md` ships in the
  repo for agent platforms that load skill files. README documents the
  recommended three-layer setup (project rules + SessionStart hook +
  skill).
- `.pi/extensions/knot-prime.ts` ships a Pi extension that runs `knot prime`
  at session start and injects the output as a hidden custom message, with a
  10s timeout, failure warning, and de-duplication of prior prime messages.
- `AGENTS.md` documents that issue tracking is managed via the `knot` CLI
  and that `.tickets/` should not be read or modified directly.

### Changed

- The `ls` command is now an alias for the canonical `list`. `knot list` and
  `knot ls` are equivalent; help renders `list` with `ls` listed under the new
  ALIASES block.
- `knot prime` suppresses the `## In Progress` heading entirely on quiet
  projects (no in-progress tickets) — empty heading-only sections were
  dead weight on every session.
- `knot prime` Commands cheatsheet trimmed from 9 lines to 7 (`knot dep`
  and `knot dep tree` moved to the bundled skill).
- `knot prime` preamble first line now references the `knot` skill so
  non-Claude agents discover the canonical reference.
- `.claude/settings.json` SessionStart hook now invokes `knot prime`
  directly; the bespoke `block-tickets-read.sh` hook was removed in favor
  of the AGENTS.md guidance.

### Removed

- `knot prime` no longer emits the `## Schema` cheatsheet. Agents reading
  the project schema should consult `.knot.edn` directly or
  `knot prime --json` for the actionable subset. (Closes
  [kno-01kqdasr0384](.tickets/archive/kno-01kqdasr0384--knot-prime-schema-section-is-hardcoded-should.md).)
- `.claude/hooks/block-tickets-read.sh` removed; agent guidance to avoid
  hand-editing `.tickets/` now lives in `AGENTS.md`.

## [0.1.0] - 2026-04-29

### Changed (breaking)

- Ticket title now lives in frontmatter (`title:` as the second key, right
  after `id:`) instead of as the first `# H1` line of the body. `knot create`
  no longer prepends `# <title>` to the body — with no `--description` /
  `--design` / `--acceptance` flags the body is empty. All read commands
  (`ls`, `ready`, `closed`, `show`, `dep tree`, `prime`) read the title
  directly from frontmatter and degrade to an empty title rather than crash
  when the field is missing. Existing ticket files in `.tickets/` were
  migrated in-place.

### Changed

- `knot create` now prefers `:default-assignee` from `.knot.edn` over
  `git config user.name` when no `--assignee` is supplied.

### Added

- `ls --json`, `ready --json`, `closed --json` now include `title` for each
  entry as a side effect of the frontmatter move.

## [0.0.1] - 2026-04-29

Initial release. Knot is a file-based ticket store for AI-assisted development:
plain Markdown tickets with YAML front-matter under `.tickets/`, queried and
mutated through a babashka CLI.

### Added

- Project setup: `knot init` walks up from cwd to write `.knot.edn`, derives a
  prefix from the project directory name, and seeds the `.tickets/` tree.
- Lifecycle transitions: `knot create`, `show`, `status`, `start`, `close`,
  `reopen`, plus automatic moves between `.tickets/` and `.tickets/archive/`
  when a ticket reaches a terminal status.
- Discovery and listing: `knot ls`, `ready`, `blocked`, `closed`, with filters
  for `--status / --assignee / --tag / --type / --mode` and a `--limit` cap.
- Dependency graph: `knot dep` / `undep` for cycle-checked edges, `dep tree`
  for the recursive view, and `dep cycle` for repo-wide cycle detection.
- Symmetric links: `knot link` and `unlink` with computed inverse sections
  rendered in `show`.
- Annotation: `knot add-note` (positional / piped / `$EDITOR`) and `knot edit`
  for full-file editing, with a `--summary` field threaded through close.
- Session priming: `knot prime` emits an agent-directive primer, safe to wire
  into a SessionStart hook even outside a Knot project.
- Structured help system: `knot --help`, `knot help <cmd>`, and per-command
  `--help` / `-h`, with a single registry as the source of truth for both the
  parser spec and the rendered help.
- JSON output: every read command accepts `--json` for machine-readable output;
  TTY-aware color discipline keeps piped output clean.
- Distribution: `bbin` install metadata in `bb.edn` and a Clojure/babashka
  install path documented in the README.
- `knot --version` and a version banner on `knot --help`.
