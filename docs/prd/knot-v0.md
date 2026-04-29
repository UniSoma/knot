---
id: prd-2026-04-28-knot-v0
title: Knot — Babashka Ticket Tracker (v0)
status: proposed
created: 2026-04-28
source: conversation
---

## Problem Statement

The user is a solo Clojure developer who wants a personal-driver ticket tracker that lives alongside source code in git. The existing tool `wedow/ticket` (`tk`) — a single-file bash script that stores tickets as markdown with YAML frontmatter — has the right shape for this use case (git-native, AI-friendly, stateless on disk) but the user has specific opinions about how it should evolve:

- A Clojure-native implementation would be more pleasant to extend than bash.
- JSON output requires `jq` as a separate dependency in `tk`; the user wants it built-in.
- `tk`'s 4-character random IDs are short but collision-prone past ~50 tickets per project.
- `tk` has no configuration mechanism; per-project tweaks (status workflow, types) require forking.
- The status workflow is hardcoded (`open | in_progress | closed`); the user wants flexibility for `review`, `wontfix`, etc.
- There is no first-class concept of whether a ticket is autonomously runnable by an AI agent versus requiring a human in the loop — a critical distinction in agent-augmented workflows.
- A future query API (e.g. Pathom3 EQL resolvers) is interesting, but the current bash implementation forecloses on it.

The user wants to keep `tk`'s strongest properties — file-based, git-native, no daemons, no synthetic database, AI-greppable — while addressing the above gaps.

## Solution

Knot is a babashka CLI ticket tracker. Tickets are markdown files with YAML frontmatter in a flat `.tickets/` directory; closed tickets auto-move to `.tickets/archive/` on terminal-status transitions and auto-restore on reopen, keeping the live workspace focused on active work. Ticket filenames are `<id>--<title-slug>.md` for at-a-glance readability in editor file trees, with the canonical ID stored in frontmatter. Project root is discovered by walking up from cwd looking for either a `.knot.edn` config file or the configured tickets directory. The CLI exposes 19 subcommands covering the lifecycle (create, status transitions, close-with-summary), graph operations (deps, links, cycle detection, dep tree), queries (`ready`, `blocked`, `closed`, filtered `ls`), annotation (`add-note`, `edit`), agent context-injection (`prime`), and project setup (`init`). Every read command supports `--json` for jq-free pipeline use.

IDs are short ULID suffixes (12 lowercase Crockford-base32 characters: 10-char timestamp + 2-char random) prefixed by a project shortcode auto-derived from the directory name (e.g. `mp-01jq8p4abcde` for project `my-project`). The format is sortable, stateless, and collision-proof for personal-scale use. ID resolution on commands is layered prefix matching, mirroring `git`'s short-hash UX.

`.knot.edn` at the project root is optional — defaults work zero-config — but exposes configurable keys for `:tickets-dir`, `:prefix`, `:default-assignee`, `:default-type`, `:default-priority`, `:statuses`, `:terminal-statuses`, `:types`, `:modes`, and `:default-mode`. The frontmatter schema mirrors `tk`'s with three meaningful additions: `external_refs` is a plural array (multiple cross-system references per ticket), `:updated` is auto-bumped on every Knot-mediated change, `:closed` is set on transitions into a terminal status and cleared on transitions out, and `:mode` distinguishes `"afk"` (agent-runnable) from `"hitl"` (human-in-the-loop) work — enabling queries like `knot ready --mode afk` to find autonomous, unblocked work to hand to an agent.

Wired into Claude Code's `SessionStart` hook, `knot prime` emits a five-section markdown primer (preamble, project metadata, in-progress tickets, ready tickets, schema and command cheatsheet) so a fresh agent gets a complete picture of active and ready work in one shot, with no chained commands required at session start. `--mode afk` filters the ready section to agent-runnable items only; `--limit N` overrides the default ready cap of 20; `--json` exposes the actionable subset (`{project, in_progress, ready, ready_truncated, ready_remaining}`) for tool consumers. Prime always exits 0 — even when run from a directory with no Knot project — so it is safe to wire into a global hook without conditional fall-throughs.

The codebase is structured into eight namespaces with two intentionally deep, pure modules (`knot.ticket`, `knot.query`) that form a Pathom3-feedable data layer. The Pathom3 resolver itself is deferred to v0.1 to keep v0 scope focused on the CLI.

Distribution is via `bbin install io.github.<user>/knot --as knot`. Implementation uses `babashka.cli/dispatch` for argument parsing and `clj-yaml` for frontmatter (de)serialization.

## User Stories

1. As a solo developer, I want to run `knot create "fix login bug"` from any subdirectory of my project, so that I can capture an issue without context-switching to the project root.
2. As a solo developer, I want short, sortable, collision-free ticket IDs, so that I can paste them into commit messages without ambiguity and skim them in chronological order.
3. As a solo developer, I want `knot show <id>` to render the full ticket with its frontmatter, body, and computed inverse relationships (Blockers, Blocking, Children, Linked), so that I see the whole graph context for a single ticket.
4. As a solo developer, I want `knot edit <id>` to open the ticket in `$EDITOR`, so that I can write a long description or restructure the body.
5. As a solo developer running AI agents, I want to mark a ticket as `--afk` at create time, so that I can later filter for agent-runnable work.
6. As a solo developer running AI agents, I want `knot ready --mode afk` to return tickets that are unblocked and agent-runnable, so that I can hand a batch of tasks to an agent in one query.
7. As a solo developer, I want `--json` output on every read command, so that I can pipe tickets through `jq` (or any tool) without installing extra deps.
8. As a solo developer, I want JSON output to use snake_case keys, so that jq filters work without quoting awkward kebab-case keys.
9. As a solo developer, I want `knot ready` to list open or in-progress tickets whose dependencies are all closed, so that I always know what I can pick up next.
10. As a solo developer, I want `knot blocked` to list tickets whose dependencies are open, so that I can break dependencies before they snowball.
11. As a solo developer, I want `knot dep tree <id>` to render an ASCII dependency tree, so that I can visualize work breakdown in the terminal.
12. As a solo developer, I want `knot dep tree <id> --full` to show duplicate branches in full, so that I see the complete subtree for analysis.
13. As a solo developer, I want adding a dependency to fail if it would create a cycle, so that I can never accidentally introduce a broken graph.
14. As a solo developer, I want `knot dep cycle` to scan for any pre-existing cycles in open tickets, so that I can audit a project that may have accumulated cycles via hand-editing.
15. As a solo developer, I want `knot link A B C` to create symmetric links across multiple tickets in one command, so that batch-relating tickets is fast.
16. As a solo developer, I want links to be maintained symmetrically — if I link A to B, B's frontmatter also references A — so that I can navigate the graph from either side without scanning the project.
17. As a solo developer, I want `knot add-note <id> "text"` to append a timestamped note to the ticket, so that I have a journal of progress.
18. As a solo developer, I want `knot add-note <id>` to read from stdin if piped or open `$EDITOR` if interactive, so that the input mode adapts to my context.
19. As a solo developer, I want partial ID matching like `git`'s short hashes — typing `knot show 01jq8p4ab` resolves to the unique full ID — so that I don't type 14 characters every time.
20. As a solo developer, I want partial IDs to also work without the project prefix (`knot show abc...`), so that I don't retype the prefix I already know I'm in.
21. As a solo developer, I want my git `user.name` used as the default assignee when `.knot.edn` doesn't pin one, and I want `:default-assignee` in `.knot.edn` (including `:default-assignee nil` to opt out of auto-assignment) to override that fallback, so that the project config — not my git identity — owns the policy on shared repos.
22. As a solo developer, I want a configurable status workflow, so that my project's conventions (e.g. adding `review`, `wontfix`) fit Knot rather than the reverse.
23. As a solo developer, I want a configurable type list, so that I can add domain-specific types without forking the tool.
24. As a solo developer, I want broken references (a `:parent` or `:deps` entry pointing to a missing ticket) to render with a `[missing]` marker and a stderr warning, so that mid-refactor states still work without aborting.
25. As a solo developer, I want recently-closed tickets queryable via `knot closed --limit N`, so that I can do retros or release notes without parsing git log.
26. As a solo developer, I want zero-config to work — drop into any repo, run `knot create`, and have it just work — so that adoption has no ceremony.
27. As a solo developer, I want `knot init` to write a self-documenting stub config with all keys present and commented, so that I can learn the schema by reading the file.
28. As a solo developer, I want commits to be authored by me, not Knot, so that my git log reflects my intent and commits can bundle code+ticket changes.
29. As a solo developer working on a single machine, I want no lock files, so that I never have to deal with stale locks after a crash.
30. As a solo developer, I want unknown frontmatter keys preserved on round-trip, so that hand-added experimental fields aren't silently dropped.
31. As a solo developer, I want body sections beyond `## Notes` to be freeform, so that I can adopt per-ticket conventions like `## Reproduction Steps` without the tool fighting me.
32. As a solo developer, I want filtering by status, assignee, tag, type, and mode on `ls`, so that I can carve up the backlog in any direction.
33. As a solo developer, I want `start`, `close`, and `reopen` shortcuts for the three most common transitions, so that frequent operations are one word.
34. As a solo developer, I want `:updated` bumped on every Knot-mediated change, so that I can sort by recent activity.
35. As a solo developer, I want `:closed` set on every transition into a terminal status and cleared on transitions out, so that "closed last week" queries are precise.
36. As a solo developer, I want ANSI color output that auto-disables when piped or when `NO_COLOR=1` is set, so that both pretty terminals and clean pipelines work.
37. As a solo developer, I want stdout to carry only data and stderr to carry warnings/errors, so that pipelines stay clean even when there are warnings.
38. As a future API consumer, I want the data layer (parse, store, query) cleanly separated from the CLI, so that a Pathom3 EQL resolver layer can be added in v0.1 without rewriting v0.
39. As a solo developer, I want a single `bbin install io.github.<user>/knot --as knot` to set up Knot, so that installation is painless and uses the bb-native package manager.
40. As a solo developer running AI agents, I want to wire `knot prime` into my Claude Code `SessionStart` hook, so that every fresh agent session starts with a primer of project state and schema without me briefing it manually.
41. As a solo developer, I want `knot prime` to emit one-line summaries (`id  mode  pri  title`) for in-progress and ready tickets, so that token cost stays predictable as the project grows.
42. As a solo developer, I want `knot prime --mode afk` to filter the ready section to agent-runnable work, so that I can hand a session straight to an autonomous agent.
43. As a solo developer, I want `knot prime --json` to emit the actionable ticket subset, so that wrapper tools can consume the same view the agent sees without re-parsing markdown.
44. As a solo developer, I want `knot prime` to always exit 0 — even in directories with no Knot project — so that wiring it into a global `SessionStart` hook can never break my agent session.
45. As a solo developer, I want closed tickets to auto-move to `.tickets/archive/`, so that the live directory only shows active work while ID resolution and graph queries still span the full history.
46. As a solo developer, I want reopening an archived ticket to auto-restore its file to the live directory, so that I never have to think about file location.
47. As a solo developer, I want ticket filenames to include a slug of the title (`<id>--<slug>.md`), so that I can recognize tickets at a glance in my editor's file tree.
48. As a solo developer, I want filenames to NOT auto-rename when I edit a title, so that `git log -- <file>` and grep workflows remain stable across the lifetime of a ticket.
49. As a solo developer, I want `knot close <id> --summary "text"` to close and append a closure note in one command, so that wrapping up a ticket with a final note is a single keystroke.

## Implementation Decisions

**Modules (eight namespaces).** Two deep pure modules carry the load: `knot.ticket` (frontmatter parse↔render, ID generation, validation; small interface, lots of behavior, no I/O) and `knot.query` (pure graph algorithms over ticket sequences: cycle detection, dep-tree, ready/blocked, filters; the natural feed for a future Pathom3 resolver). Two medium modules wrap them with policy: `knot.store` (filesystem boundary; load-all/load-one/save!; centralizes `:updated`/`:closed` timestamping and symmetric link maintenance) and `knot.config` (`.knot.edn` parsing plus walk-up project discovery). Four shallow modules form the boundaries: `knot.cli` (argument specs and dispatch), `knot.main` (entry point and exit codes), `knot.output` (human/JSON/dep-tree renderers, color/TTY detection), and `knot.git` (single function reading `git config user.name`).

**File format.** Markdown body with YAML frontmatter, parsed and rendered by `clj-yaml`. Body conventions are soft: only `## Notes` is semantically meaningful (anchor for `add-note` appends); everything else is freeform markdown. `## Blockers`, `## Blocking`, `## Children`, `## Linked` are computed at `show` time, not persisted.

**Slug filenames.** Tickets are stored at `<tickets-dir>/<id>--<title-slug>.md`. The slug is derived once at create time from the ticket's H1 title: lowercase, non-alphanum mapped to a single hyphen, runs of hyphens collapsed, leading/trailing hyphens trimmed, Unicode stripped (not transliterated), max 50 chars truncated at the last hyphen ≤ 50. Empty title produces a bare `<id>.md` (no separator, no trailing `--`). Slugs are stable: editing the title later does not rename the file, and `knot edit` never renames. The frontmatter `:id` is canonical for ID resolution; manual `git mv` to refresh a stale slug is always safe because resolution does not depend on filename. ID resolution scans both `<tickets-dir>/*.md` and `<tickets-dir>/archive/*.md` via glob, so partial-ID matches work regardless of the slug suffix. There is no config knob to disable slugs; the convention is fixed.

**ID format.** `<prefix>-<12-char lowercase Crockford-base32 ULID suffix>`, where the suffix is 10 timestamp chars + 2 random chars. Prefix is auto-derived from the project directory name (first letter of each `[-_]`-separated segment, falling back to first 3 chars when too short) and overridable via `.knot.edn`'s `:prefix`. ID resolution is layered: exact full match wins; otherwise prefix match against the full ID; otherwise prefix match against the post-prefix ULID portion (so `01jq8p4` works without retyping `mp-`); ambiguous matches fail with a candidate list.

**Frontmatter schema.** Keys: `id`, `status`, `deps` (array), `links` (array), `parent`, `type`, `priority` (int 0–4, 0 = highest), `assignee`, `tags` (array), `created` (ISO 8601 UTC), `updated` (ISO 8601 UTC, auto-bumped), `closed` (ISO 8601 UTC, set on terminal transitions), `external_refs` (array; plural), `mode` (`"afk"` | `"hitl"`). On serialization, kebab-case is the internal/EDN convention; the JSON output boundary uses snake_case (e.g. `external_refs`, `default_mode`). Unknown frontmatter keys are preserved on round-trip.

**Storage layout.** Tickets live at `<tickets-dir>/<id>--<slug>.md` (live) or `<tickets-dir>/archive/<id>--<slug>.md` (archived). The tickets directory is configurable via `.knot.edn`'s `:tickets-dir` (default `.tickets`); the archive directory is fixed at `<tickets-dir>/archive/` with no separate config key. Status lives in frontmatter; on every save through `knot.store/save!`, the store reconciles the file's directory with whether the ticket's status is in `:terminal-statuses` (terminal → archive, non-terminal → live), so hand-edits that desync location and status self-heal on the next Knot-mediated write. Both directories are loaded into the in-memory ticket set on every command, so ID resolution, `dep tree`, broken-reference checks, and `closed --limit N` all span the full history transparently. Default `ls` continues to filter non-terminal as it already does, so the archive is naturally hidden from the default backlog view.

**Archive.** Auto-archive is a side effect of any status transition crossing the `:terminal-statuses` boundary: `close` and `status X <terminal>` move the file from live to `<tickets-dir>/archive/`; `reopen` and `status X <non-terminal>` move it back. The slug suffix is preserved across moves. The archive layout is flat (`<tickets-dir>/archive/<id>--<slug>.md`); date-sharded layouts and a configurable `:archive-dir` key are explicitly parked for v0.1+. Symmetric links survive archive transitions because `:links` references IDs, not paths — only the archived ticket's own file moves; linked siblings are untouched. There is no separate `knot archive <id>` command; archive is implicit in the status transition.

**Config (`.knot.edn` at project root).** Optional. Schema: `:tickets-dir`, `:prefix`, `:default-assignee`, `:default-type`, `:default-priority`, `:statuses` (ordered list), `:terminal-statuses` (set), `:types` (list), `:modes` (list), `:default-mode`. Defaults: tickets-dir = `.tickets`, default-type = `task`, default-priority = `2`, statuses = `["open" "in_progress" "closed"]`, terminal-statuses = `#{"closed"}`, types = `["bug" "feature" "task" "epic" "chore"]`, modes = `["afk" "hitl"]`, default-mode = `"hitl"`. Invalid keys warn; invalid values fail at command start. Project discovery walks up from cwd; the first directory containing either `.knot.edn` or `<tickets-dir>/` is the project root. Config wins on conflict.

**Subcommand surface (19).** Project: `init`, `prime`. Lifecycle: `create`, `status`, `start`, `close`, `reopen`. Read: `show`, `ls`, `ready`, `blocked`, `closed`. Relationships: `dep`, `undep`, `dep tree`, `dep cycle`, `link`, `unlink`. Annotation: `add-note`, `edit`. `--json` flag is supported on every read command (`show`, `ls`, `ready`, `blocked`, `closed`, `dep tree`, `prime`). `close` and `status X <terminal>` accept `--summary "text"` to append a closure note under `## Notes` in the same operation. Plugin discovery and `migrate-beads` are explicitly cut.

**CLI parser.** `babashka.cli/dispatch` from `knot.main` routes into per-command handlers in `knot.cli`. Nested subcommands (`dep tree`, `dep cycle`) are handled via `:cmds` vectors matching multiple tokens. Help is generated by `babashka.cli`'s built-in support.

**`create` flags.** `-d/--description`, `--design`, `--acceptance`, `-t/--type`, `-p/--priority`, `-a/--assignee`, `--external-ref` (repeatable, populates `external_refs`), `--parent`, `--tags` (comma-separated), `--mode` (canonical), `--afk` and `--hitl` (sugar). Body sections are written only when their flag is supplied; no empty section placeholders.

**Output (`knot.output`).** ANSI color when stdout is a TTY; auto-disabled on pipe, `NO_COLOR`, or `--no-color`. Status colored cyan/yellow/dim; priority 0 red+bold; mode and type as faint badges. `ls` table columns: `ID  STATUS  PRI  MODE  TYPE  ASSIGNEE  TITLE`, PRI right-aligned, TITLE truncated on TTY and full-width when piped. `dep tree` uses ASCII box-drawing characters, dedupes already-seen branches with `↑` by default, shows full duplicates with `--full`. JSON output: bare object for `show`, bare array for list commands, nested map for `dep tree` (no envelope wrapping). Stdout = data only; stderr = warnings, info, errors.

**Prime.** `knot prime` emits a five-section markdown primer to stdout: (1) a one-paragraph preamble; (2) project metadata — prefix, project name (if config provides one), and live/archive ticket counts; (3) in-progress tickets sorted by `:updated` desc, no limit, one line each as `id  mode  pri  title`; (4) ready tickets (open + non-blocked) sorted by priority asc then `:created` desc, capped at 20 by default with a `... +N more (run \`knot ready\`)` truncation footer, one line each in the same shape; (5) a schema and command cheatsheet referencing the frontmatter keys and the subset of commands an agent is most likely to invoke. Flags: `--mode afk` filters the ready section to agent-runnable tickets (filter applied before limit); `--limit N` overrides the ready cap; `--json` emits an actionable subset (`{project, in_progress, ready, ready_truncated, ready_remaining}`) with snake_case keys, dropping the preamble, schema, and command cheatsheet (consumers know the schema by definition). `prime` always exits 0 — including when no project is found by walk-up (preamble in that case directs the user to `knot init`), when the project has zero tickets, or when only archived tickets exist — so the command is safe to wire into a global Claude Code `SessionStart` hook without conditional fall-throughs. Hook setup is documented in the README; `knot init` does not modify `.claude/settings.json` or any other agent config.

**Editor and `add-note` input.** Editor resolution: `$VISUAL → $EDITOR → nano → vi`. `add-note` input is layered: explicit text arg wins; if absent and stdin is not a TTY, read stdin; otherwise open the editor with a temp file containing a `# Lines starting with '#' will be ignored.` header and a context line. Empty content cancels with no file change. Notes are appended under `## Notes` as `**<ISO timestamp>**\n\n<body>` blocks.

**Close summary.** `close <id>` and `status <id> <terminal-status>` accept an optional `--summary "text"` flag that appends a timestamped note to the ticket's `## Notes` section in the same operation, using `add-note`'s identical writer code path and timestamp format. The `:closed` frontmatter timestamp remains the structured close marker; the note carries the prose. `--summary` errors at command start when the new status is non-terminal (`start`, `reopen`, or any `status X <non-terminal>` transition). `--summary` accepts a string value only — no stdin, no editor; longer prose is composed via shell expansion (`--summary "$(cat resolution.md)"`) or via a separate `add-note` invocation. Empty string is a no-op for the note (close still happens; nothing appended). Notes appended via `--summary` are not removed on `reopen`; they remain as historical journal entries.

**Timestamps.** `:updated` is set by `knot.store/save!` on every persisted change (centralized; no scattered policy). `:closed` is set when the new status is in `:terminal-statuses` and the prior status was not (or was different terminal); cleared when the new status is non-terminal. Reopen removes `:closed` from frontmatter entirely.

**Graph consistency.** Symmetric links: `link` and `unlink` write to both ticket files; idempotent on each side. Referential integrity is lazy: broken `:deps`, `:links`, or `:parent` references render with a `[missing]` marker and a stderr warning, never abort. Cycle detection runs on every `dep` add (DFS from the new dep target looking for the source); rejects with the offending path. The explicit `knot dep cycle` command is a project-wide DFS scan over open tickets.

**Git integration.** Read-only: `git config user.name` is consulted once per command run as a fallback for the default assignee — only when `:default-assignee` is absent from `.knot.edn`. When that key is present (including set to `nil` to opt out of auto-assignment), config wins and git is not consulted. Knot never runs `git add`, `git commit`, or `git push`. Works in non-git directories (assignee resolves to `nil` when both config and git are unset).

**Concurrency.** No locking. Reads are slurp-then-parse; writes are full-file replacements; last writer wins. Personal-use scope makes locking's complexity unjustified.

**Validation.** Lenient. Unparseable YAML in a ticket file → stderr warning, skip the ticket, continue. Required fields missing → same. Unknown keys preserved on round-trip. Invalid config values fail fast at command start with a clear message.

**`knot init`.** Writes a self-documenting `.knot.edn` stub with all default keys present and inline-commented, so the schema is discoverable by reading the file. Creates `tickets-dir` if missing. Optional flags: `--prefix`, `--tickets-dir`, `--force` (overwrite existing config). Otherwise aborts on existing config.

**Exit codes.** `0` on success, `1` on any failure. Failure messages always go to stderr.

**Distribution.** `bbin install io.github.<user>/knot --as knot`. No Homebrew tap, AUR, or Makefile in v0. `bb.edn` declares `clj-yaml` as the only non-bb dep.

**Pathom3.** Deferred to v0.1. The deep-module split (pure `knot.ticket` and `knot.query`, I/O isolated to `knot.store`) is the v0 deliverable that makes Pathom3 cheap to add later.

## Testing Decisions

Tests focus on external behavior, not implementation details: parse↔render correctness, query results, observable filesystem effects, observable warning output. Internal helpers are not tested directly. Tests use `clojure.test` via the standard `bb test` task pattern; no BDD framework (unlike `tk`'s use of `behave`).

The four core modules are tested:

- **`knot.ticket`** — frontmatter parse↔render round-trip (including unknown-key preservation), ID format conformance (length, character set, prefix derivation rules), schema validation (required fields, allowed enum values for `status`/`type`/`mode`), slug derivation (lowercase + non-alphanum → hyphen, run-collapse and edge-trim, Unicode stripping, 50-char truncation at the last hyphen ≤ 50, empty-title fallback to bare `<id>.md`).
- **`knot.query`** — cycle detection (positive cases including self-loops and multi-cycles, negative cases on legal DAGs), `ready` and `blocked` partitioning under various dep states, `dep tree` dedup with `↑` markers and `--full` parity, filter composition (status × assignee × tag × type × mode), prime ordering rules (in-progress by `:updated` desc; ready by priority asc then `:created` desc; `--limit` and `--mode afk` interactions, with filter applied before limit).
- **`knot.store`** — temp-directory integration tests: load-all (across both live and archive directories), save! correctly stamping `:updated` on every write, `:closed` set/cleared on terminal transitions, archive auto-move on terminal transitions and auto-restore on reopen (slug suffix preserved across moves), file-location self-healing when a hand-edit places a terminal-status ticket in the live directory (or vice versa) and is then saved through Knot, symmetric link write across both ticket files (links survive archive transitions because `:links` references IDs not paths), `--summary` appending a `## Notes` entry matching `add-note`'s format, broken-reference warning emitted to stderr without aborting, lenient YAML failure (warn-and-skip).
- **`knot.config`** — walk-up discovery (cwd nested several levels deep, both markers present at different ancestors, conflicting `:tickets-dir` between config and on-disk directory), default merging when keys are absent, validation errors on bad values.

Shallow boundary modules (`knot.cli`, `knot.main`, `knot.git`) are not unit-tested in v0; they're exercised indirectly when integration tests run real commands. A small smoke-test layer can be added later if regressions accumulate. `knot.output`'s prime renderers are tested directly given prime's contractual stability (its markdown structure is consumed by AI agents and its `--json` shape is consumed by tools): five-section structure, sort order in each ticket section, truncation footer when ready is capped, `--json` payload shape and key set, and exit-0 behavior on missing-project and empty-project states.

Prior art for this style of testing exists across the babashka and Clojure ecosystems — `clojure.test` with `(deftest ...)` and `(is ...)` assertions, temp-dir helpers via `babashka.fs`, no mocking of internal collaborators (deep modules are pure and need no mocks; the filesystem boundary is exercised against real temp dirs).

## Out of Scope

- **Pathom3 resolver layer.** Deferred to v0.1; v0 only ensures the data layer is clean enough to support it.
- **MCP server with single GraphQL/EQL tool.** A `knot mcp` command exposing the planned Pathom3 EQL resolver via stdio MCP — a single `knot_eql` tool whose description carries the schema, letting agents craft exactly the queries they need with minimal tool surface area. Pattern borrowed from `hmans/beans` (beans-kp3h). Parked for v0.1+ alongside the Pathom3 resolver itself; the v0 module split is the structural deliverable that makes this cheap to add later.
- **Plugin discovery.** No `tk-<cmd>` PATH discovery, no `super` bypass. v0 has a closed command set.
- **Migration tools.** No `migrate-beads`, no `tk → knot` importer (the file formats are similar enough that direct copy works for many cases, but Knot does not promise compatibility).
- **Auto-commit and git hooks.** Knot never writes to git history. A `:auto-commit` config flag is parked for a future version. A `prepare-commit-msg` hook to inject ticket IDs is also parked.
- **Web UI, multi-user sync, server mode.** Personal-driver scope.
- **Concurrency primitives.** No file locking, no fcntl, no `.knot.lock`. Stays out unless a real-world use case forces it in.
- **Status transition rules.** `:transitions` enforcement is parked; v0 allows any-to-any transitions (matching `tk`).
- **Priority labels.** Priority is integer 0–4 only; named labels (P0/urgent/etc.) are a render concern parked for later.
- **Index / cache.** Full reparse on every command is fine at personal scale; caching is parked until proven necessary.
- **Shell completion scripts.** Bash/zsh/fish completion would mitigate the longer-than-`tk` IDs but is parked. Layered prefix matching softens the ID-length cost in v0.
- **Hash-and-skip optimization on no-op `edit`.** A `knot edit` that touches the file without changing content still bumps `:updated`. Acceptable in v0; could be refined later.
- **`:editor` config key.** Editor is selected via `$VISUAL → $EDITOR → fallbacks` only.
- **BDD test framework.** `clojure.test` is enough at this scale.

## Further Notes

- The personal-driver framing (Q1 of the design conversation) is the load-bearing assumption behind many of the cuts above. If audience widens (team use, public release), several parked items become near-term — most importantly transition rules, locking, and possibly auto-commit.
- IDs are longer than `tk`'s by design (12 ULID chars + prefix vs 4 random chars + prefix). This is the accepted cost of sortability and stateless collision-resistance. Layered prefix matching keeps day-to-day typing close to `tk`'s ergonomics.
- The `:mode` field is the most novel addition vs `tk`. The query `knot ready --mode afk` is the killer use case — surfacing unblocked, agent-runnable work in a single command — and is the reason the field is a peer dimension to `:status` and `:priority` rather than a tag.
- The Pathom3-readiness deliverable in v0 is structural, not behavioral: an outside reader of the codebase should be able to look at `knot.ticket`, `knot.query`, and `knot.store` and see a layer that an EQL resolver could feed off without rework. This is the only reason v0 has eight namespaces instead of one or two — for a strict CLI-only deliverable, a flatter layout would be defensible.
- Several v0 refinements are direct borrowings from `hmans/beans` (a Go-based AI-friendly issue tracker with the same fundamental shape as `tk` and Knot): the `<tickets-dir>/archive/` subdirectory for closed tickets, the `<id>--<slug>.md` filename pattern, the `prime`-style `SessionStart` context-injection command, and `close --summary` for one-shot close-with-prose. The MCP-with-single-EQL-tool pattern (beans-kp3h) is parked alongside Knot's Pathom3 plans for v0.1+. None of these change Knot's underlying philosophy; they are ergonomic borrowings from a tool with overlapping design instincts.
