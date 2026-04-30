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

### Removed

- `knot prime` no longer emits the `## Schema` cheatsheet. Agents reading
  the project schema should consult `.knot.edn` directly or
  `knot prime --json` for the actionable subset. (Closes
  [kno-01kqdasr0384](.tickets/archive/kno-01kqdasr0384--knot-prime-schema-section-is-hardcoded-should.md).)

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
