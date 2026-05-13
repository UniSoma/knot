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

- **`--priority N` filter on listing commands.** `knot list`, `ready`,
  `blocked`, `closed`, and `prime` now accept `--priority` (repeatable,
  integer 0..4) so callers can scope results by priority — e.g.
  `knot ready --priority 0` for top-priority work, or
  `--priority 0 --priority 1` for the high band. Out-of-range values
  are rejected at parse time with a clear message; unlike the open
  enums (`--type`, `--status`), priority is a closed range across
  config/check.

## [0.4.0] - 2026-05-13

### Added

- **New Emacs UI: `emacs/knot.el`.** Single-file magit-style mode
  fronting the `knot` CLI. Project detection mirrors `magit-toplevel`
  via cached `knot info --json`. `M-x knot` opens a dispatch transient.
  Every CLI subprocess goes through one boundary function
  (`knot-cli-call`) that parses `--json` and surfaces `ok:false`
  envelopes as `user-error` in the minibuffer. Buffer names are
  project-qualified throughout (`*knot-list: <project>*`,
  `*knot-show: <project> · <id>*`, etc.) so the mode is multi-project
  safe. A startup `lwarn` fires when the on-disk `knot` is older than
  `knot-minimum-cli-version` (currently 0.3.0). A new `bb lint:elisp`
  task byte-compiles `emacs/knot.el` and runs `package-lint`.
  - **List buffer** (`knot-list-mode`): one project-scoped
    `tabulated-list` buffer with `l`/`r`/`b`/`c` view switching across
    list/ready/blocked/closed, a filter transient on `f`, and a sort
    transient on `o` with single-key suffixes
    (id/title/priority/status/type/mode/created/updated, `d` toggle
    direction, `R` reset to view default). Per-view default orderings
    (list/ready/blocked → priority asc with id tiebreak; closed →
    updated desc) hydrate when unset. Sort is client-side over the
    buffered rows so toggling never re-hits the CLI. `,` (or `M` under
    `knot-evil-mode`) opens the update transient on the row at point.
  - **Show buffer** (`knot-show-mode`): `markdown-view-mode` rendering
    with buttonized ids, `RET`-on-AC flips done/undone, and `RET` on
    any other editable frontmatter field
    (status/type/priority/mode/assignee/parent/tags) opens the matching
    update prompt. Dep / link rows support remove-at-point.
  - **Create transient** (capital-`C` quick-create + full transient
    with per-flag readers) lands post-create in the show buffer on
    `## Description`. Deps and links autocomplete against live and
    archived tickets.
  - **Update transient** covers
    status/priority/mode/type/tags/assignee/parent inline (no buffer
    pops), with a Long-form group that routes
    description/design/body/note edits to capture buffers.
  - **Capture buffers** for long-form fields commit via `C-c C-c`
    (`knot update --description|--design|--body` or `knot add-note`)
    and discard via `C-c C-k`. Capital-`E` is an escape-hatch to
    `knot edit` via emacsclient.
  - **Deps tree buffer** (`knot-deps-mode`) renders `knot dep tree
    --json` with status glyphs and buttonized nodes.
  - **Cross-buffer refresh** propagates mutations across sibling
    list/show/deps buffers in the same project, preserving point on
    the originating row id.

- **Evil / Doom support: `knot-evil-mode`** is a `:global t` opt-in
  minor mode wrapping a soft `(require 'evil nil t)`. Enabling it
  destructively rewires `knot-list` / `knot-show` / `knot-info` /
  `knot-deps` mode-maps via `evil-define-key*` in each mode's
  normal-state auxiliary keymap, and sets normal initial state on the
  read-only modes (insert on capture; `C-c C-c` / `C-c C-k` commit and
  discard regardless of state). Setup is data-driven through
  `knot-evil--stock-keys` / `knot-evil--bindings` /
  `knot-evil--initial-states` and idempotent. `evil` is a soft
  dependency — the package loads without it and toggling the mode on
  raises `user-error` when evil is missing. `emacs/README.md`
  documents install, the full binding table, and a paste-ready Doom
  `use-package!` + `map! :localleader` snippet.

- `knot create` now **hints when a value starting with `-` is
  mistaken for a flag** by babashka.cli. Detected via the spec-shape
  signature (a flag listed in the `:spec` lands in `:opts` as implicit
  `true` / `[true]` despite not being `:coerce :boolean`); the cryptic
  `Unknown option: :e` / `Unknown option: :test` message is replaced
  with an actionable hint pointing at the `--<flag>=<value>` form and
  the broader pre-extract follow-up
  ([kno-01kr0129m0y9](.tickets/kno-01kr0129m0y9--pre-extract-dash-leading-safe-handling-for-value.md)).
  Genuine unknown-flag errors are unaffected.

- `knot check` emits a new `legacy_acceptance_section` **warning**
  (severity `warning`, not `error`) when a ticket body still contains
  a `## Acceptance Criteria` heading after the v0.3 frontmatter
  migration. Filterable by `--code legacy_acceptance_section` and
  `--severity warning`. Self-cleans: the warning disappears after
  `knot migrate-ac` lifts the section into structured frontmatter.
  Closes the silent half-broken state that v0.2 → v0.3 upgraders hit
  when they skipped the migration — the only prior discovery path was
  reading the CHANGELOG.

- Acceptance criteria are now **load-bearing on terminal transitions**.
  `knot close`, `knot status <id> <terminal>`, and `knot update <id>
  --status <terminal>` all enforce a v0.3 acceptance gate: when the
  source status is `:active-status` and any frontmatter `:acceptance`
  entry has `done: false`, the transition is blocked. Plain text on
  stderr lists the unchecked count, indented open titles, and the
  `--check` / `--force --summary` hint. JSON: new error code
  `acceptance_incomplete` with `error.open_acceptance: [{title}, ...]`
  on stdout. Exit 1.
- `--force` flag on `knot close`, `knot status`, and `knot update` —
  bypasses the gate when paired with a non-blank `--summary`. The
  summary is appended as a Notes entry and serves as the override
  record. `--force` without `--summary` (or with a blank one) fails
  `invalid_argument`. When the gate would not fire, `--force` is a
  silent no-op.
- `knot update` gains `--status <new>` and `--summary <text>`. AC
  mutations (`--ac --done`, `--add-ac`, `--remove-ac`) apply *before*
  the gate, so `knot update <id> --ac "last AC" --done --status closed`
  checks then closes in one disk write.
- `knot.acceptance` exposes three pure predicates used by the gate and
  by listing-side AC progress: `complete?`, `progress` (returns `[done
  total]`), `open-titles`.
- **AC progress is now visible in listing tables.** `knot ls`, `knot
  ready`, `knot blocked`, and `knot closed` gain a conditional `AC`
  column (rendered as `d/t`, e.g. `2/5`) inserted immediately before
  `TITLE`. The column header and the column itself are omitted entirely
  when no ticket in the result set carries `:acceptance`, so quiet
  projects don't pay the width cost. Tickets without AC render as `-`.
  Force-closed tickets render their partial counts (`2/5`) as an audit
  signal. `ls --json` is unchanged — raw `:acceptance` already passes
  through.
- **AC progress is now visible in `knot prime`.** The In Progress and
  Ready row shapes gain a conditional AC slot before `title` (7 cols
  for In Progress / 6 for Ready when any ticket in the section has AC,
  unchanged otherwise). The renderer is whitespace-only — same shape
  for AI agents and downstream tools.
- **New `## Ready to close` section in `knot prime`.** Renders between
  `## In Progress` and `## Ready` and surfaces active-status tickets
  whose every acceptance entry is checked — the natural call-to-action
  prompt that pairs with the close-gate. Uses the In Progress line
  shape (with age column), sorted by `:updated` descending, uncapped,
  omitted entirely when empty. HITL nudge: "All acceptance criteria
  are checked — close with `knot close <id> --summary "..."`." AFK
  nudge: "Close these before grabbing new tickets." `prime-cmd`
  partitions active tickets so a ticket appears in either
  `:ready-to-close` or `:in-progress`, never both.
- **`prime --json`** gains a `data.ready_to_close` array parallel to
  `in_progress`, `ready`, and `recently_closed`, using the same
  body-less compact ticket projection. No derived `acceptance_progress`
  field on per-ticket projections — JSON consumers needing the raw AC
  list use `ls --json` or `show --json`.
- `knot.query/ready-to-close?` predicate: `(and (= status active-status)
  (seq ac) (acceptance/complete? ac))`. Vacuously-complete tickets (no
  AC list) deliberately do not migrate — only tickets with an explicit
  fully-checked checklist count as ready-to-close.

### Changed

- `knot close` no longer succeeds unconditionally on an active ticket
  with unchecked acceptance criteria. This is intentionally a behavior
  break: prior to v0.3 the criteria were stored but never enforced.
  Projects with multi-terminal configs (e.g. `:terminal-statuses
  #{"closed" "wontfix"}`) hit the gate on `in_progress → wontfix` too
  — the documented escape hatch is `--force --summary "wontfix:
  <why>"`, where the summary becomes the abandonment record.
- `knot prime` directive content overhauled against v0.3's CLI surface:
  - **HITL preamble** swaps the old 7-row "When the user says..." table
    for an 8-row mapping that adds the agent-write verb (`knot update`)
    and an explicit `show`/`list`/`dep tree` row, with inline filter
    annotations on the read-row (`--mode afk`, `--tag`, etc.). The
    closing pointer is rephrased to "For less-common ops (`info` /
    `check` / `link` / `reopen` / `--json` shapes / partial-id
    contract), invoke the `knot` skill" so the directive frames the
    skill as the long-tail reference rather than duplicating its
    contents.
  - **AFK preamble** inserts a `knot update <id>` step between
    `add-note` and `close` and tags it with a "never use `knot edit`,
    it opens $EDITOR and will fail without a TTY" anti-pattern. The
    skill pointer trims `lifecycle` (subsumed by the explicit verbs in
    the autonomous-flow checklist).
  - **`## Commands` cheatsheet retired entirely.** The preamble's
    intent table plus the bundled `knot` skill cover the same ground
    without per-session token cost. `prime --mode afk` agents in
    particular benefit — the cheatsheet was redundant with the
    autonomous-flow checklist.
  - **In Progress row format** is now `id  type  mode  pri  age  title`
    (6 cols). The `age` column renders the relative `:updated` delta as
    `Nd` (<14d), `Nw` (14d–6w, floor by 7), or `Nm` (>6w, floor by 30);
    missing `:updated` renders as `-`. The binary `[stale]` text prefix
    is retired; the age column carries the staleness signal in human-
    readable form. The `prime --json` `stale: true` flag on
    `data.in_progress[]` entries is **preserved** (set when `:updated`
    >= 14d) — the documented text/JSON asymmetry is intentional.
  - **Ready row format** is now `id  type  mode  pri  title` (5 cols),
    with the `type` column inserted between `id` and `mode`. Missing
    fields render as `-`.
  - **Recently Closed summaries** truncate at the first paragraph
    boundary (`\n\n`) at display time, with an additional 280-char
    hard cap on long single-paragraph summaries. When truncation fires,
    the line ends with ` (see knot show <id>)` so agents know where the
    rest lives. `prime --json data.recently_closed[].summary` keeps the
    full untruncated string.
  - **Per-section nudges** are now mode-conditioned. HITL is unchanged
    ("Resume here if the user picks up mid-stream." / "If asked
    'what's next', recommend the top entry..."). AFK drops the Ready
    nudge entirely (the autonomous-flow checklist already covers it)
    and rephrases the In Progress nudge to drop the "user" reference:
    "Finish your in-progress work before grabbing new tickets."
  - `prime --json` payload shape is **unchanged**: same keys on
    `data.in_progress[]` / `data.ready[]` / `data.recently_closed[]`,
    no `schema_version` bump, no new fields. The redesign is a display
    refresh; the contract is stable.

## [0.3.0] - 2026-05-06

### Added

- `knot create` gains repeatable `--dep <id>` and `--link <id>` flags
  to wire a new ticket into the graph at create time. `--dep` is
  lenient on missing targets (kept verbatim as a forward ref, matching
  `knot dep`'s tolerant-target contract); `--link` is strict (every
  target must resolve uniquely, or the command fails before any file
  is written). Both accept partial ids, dedupe equivalents that
  resolve to the same ticket, and may name archived targets — a
  reciprocal `--link` write does not unarchive. `--dep X --link X` is
  allowed and records both relationships. If multiple strict inputs
  are bad, the first failure in left-to-right CLI order wins. A
  reciprocal-link write failure rolls back: applied recip links are
  reverted and the new ticket file is deleted. Plain text errors use
  the `knot create:` prefix; `--json` returns the standard
  `not_found` / `ambiguous_id` / `invalid_argument` envelope. Bundled
  skill kept in sync.

- New `test/knot/json_contract_test.clj` namespace pins the v0.3
  `--json` envelope contract for every read and mutating command at
  `bb test` time: schema_version + ok + data XOR error invariants
  asserted centrally; per-command `data` shape (key presence + types)
  asserted per command; the four ticket vector defaults
  (`tags`/`deps`/`links`/`external_refs`) always-array contract pinned
  on read and mutating envelopes; `meta.archived_to` pinned on
  `close --json` and any `status --json` transition to a terminal
  status; the four error envelopes — `not_found` (every id-resolving
  command), `ambiguous_id` with `candidates`, `cycle` with the path
  vector on `dep --json`, and the `check --json` exit-2 cannot-scan
  envelope. Also pins the documented behavior asymmetries (`dep`/
  `undep`/`unlink` `to`-side soft resolution; `dep tree` tolerant
  unknown root; `knot check`'s `ok:false`-with-data exception). Adds
  `knot.json-contract-test/with-tmp` to `.clj-kondo/config.edn` so
  the lint baseline stays at 4 errors / 5 warnings, all pre-existing.

- `knot update` gains `--add-tag <t>` and `--remove-tag <t>`
  (repeatable) for per-tag deltas, complementing the existing
  whole-list `--tags <comma-list>`. Mutually exclusive with `--tags`
  and with each other on the same value (overlap rejected as
  `invalid_argument`). Per-tag idempotent: adding a present tag or
  removing an absent one is a no-op. Existing order is preserved;
  removes drop in place; adds append at the end in flag order. An
  empty resulting set clears the `:tags` key (consistent with
  `--tags ""`). Values are trimmed; blank or comma-bearing values
  are rejected to preserve the round-trip invariant that any tag can
  be expressed via `--tags`.
- Acceptance criteria are now structured frontmatter
  (`acceptance: [{title, done}]`) instead of freeform `- [ ]` checkboxes
  in the body. The `## Acceptance Criteria` section is **never stored**
  on disk — `knot show` synthesizes it from frontmatter at display
  time, between the body and the inverse sections, exactly like
  `## Linked` / `## Blockers` are synthesized today. Single source of
  truth; no positional-index ambiguity.
  - `knot create --acceptance "<title>"` is now a repeatable
    string flag (model: `--external-ref`). Each occurrence appends one
    entry with `done: false`. The dash-prefixed body-flag pre-extraction
    no longer covers `--acceptance` because criterion titles are short
    strings, not multi-line markdown. Existing `--description` /
    `--design` body flags are unchanged.
  - `knot update --ac "<title>" --done` (or `--undone`) flips a single
    frontmatter entry. The title must match exactly (case-sensitive).
    `--done` and `--undone` are mutually exclusive; `--ac` requires
    one of them; `--done` / `--undone` each require `--ac`. Adding /
    removing AC entries is deferred — use `knot edit` for now.
  - `knot list --acceptance-complete=false` (also on `ready`,
    `blocked`, `closed`) keeps only tickets with at least one undone
    AC. `=true` keeps tickets where every AC is done. Tickets with no
    `:acceptance` list are excluded from both filters — the dimension
    is "completion of structured acceptance work", and absent ACs
    mean that dimension does not apply.
  - `knot check` gains an `acceptance_invalid` validator that catches
    malformed entries: non-list `:acceptance`, non-map entries, missing
    or non-string `:title`, missing or non-boolean `:done`. One issue
    per offending entry; the validator runs unconditionally.
  - **Migration**: a one-shot `knot migrate-ac` command (hidden from
    top-level `knot help`) lifts every body's `## Acceptance Criteria`
    section into structured frontmatter, then strips the section.
    Both checkbox bullets (`- [ ] / - [x] / - [X]`) and plain bullets
    (`- title`) are lifted; plain bullets default to `done: false`.
    Idempotent on already-migrated tickets — safe to re-run.

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
  `--design`. (Acceptance criteria are no longer body content under
  v0.3 — see the structured-frontmatter entry above; flip a single
  AC's done state with `--ac "<title>" --done|--undone`.) `--body
  <text>` replaces the *whole* body and is destructive — there is
  **no `--force` ceremony**; git is the documented undo path. `--body`
  is mutually exclusive with the sectional body flags. `--json` returns the v0.3 success envelope
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

- New top-level `Concurrency` section in the README explains the
  no-locking model, points at git as the conflict-detection / undo
  path, and links to the optimistic-concurrency placeholder ticket
  for projects that need multi-writer coordination later.

- New `.claude/skills/knot/references/json-protocol.md` is the
  canonical reference for the v0.3 `--json` envelope: envelope shape,
  `ok` discriminator (with the `knot check` carve-out), `meta` slot,
  schema versioning, partial-id contract (strict vs soft resolution),
  error-code catalogue (`not_found`, `ambiguous_id`, `cycle`,
  `invalid_argument`, `no_project`, `config_invalid`), per-command
  `data` shape tables (read + mutating), `knot check` issue-code
  catalogue, and worked examples for each envelope variant. Lives
  under the bundled skill folder so projects that copy the skill
  inherit the protocol contract alongside it. Mirrors the contract
  pinned by `test/knot/json_contract_test.clj` so prose drift is
  caught at `bb test` time. README's JSON paragraph and
  `SKILL.md`'s JSON section both link here.

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

- `knot update` gains `--add-ac <title>` and `--remove-ac <title>`
  for non-flip AC list management. Both repeatable, idempotent, and
  match by exact title. Composes with `--ac <title> --done|--undone`
  in apply order add → flip → remove, so a single `update` call can
  add a new AC, flip an existing one, and remove a third in the same
  edit. `--body` now warns that the `## Acceptance Criteria` section
  is display-only on write; `--ac` points to the new deltas for
  non-flip ops. Bundled skill kept in sync.

### Changed

- Every command is now a strict-parsing command: unknown flags
  (`--tag` instead of `--tags`, `--bogus`, anything mistyped) exit
  non-zero with `knot: Unknown option: :<name>` on stderr instead of
  being silently absorbed by the parser. The `:restrict?` mechanism
  was already in place on `prime`/`ready`/`blocked`/`closed`/`info`/
  `migrate-ac`/`create`; this flips it on the remaining sixteen
  entries (`init`/`show`/`list`/`status`/`start`/`close`/`reopen`/
  `dep`/`dep tree`/`undep`/`link`/`unlink`/`add-note`/`edit`/
  `update`/`check`) so the contract is uniform across the CLI. A
  registry-invariant test pins the rule (`every?` `:restrict?` true
  on `help/registry`); a new entry that omits it fails `bb test`.
  Bundled with a small `edit-handler` cleanup so the `:edit` entry
  flows through `(spec :edit)` like every other command — no more
  hard-coded `{:spec {}}`. Pre-1.0 break window: anyone scripting
  `knot` with stale or misspelled flag names (e.g. `--tag` on
  `create`) gets a loud failure instead of silent argv theft.
  Migration: run `knot <cmd> --help` to see the canonical flag
  names. Bundled skill kept in sync.

- The intake status used by `knot create` and `knot reopen` is now
  derived from the project's `:statuses` / `:active-status` /
  `:terminal-statuses` config (the first non-active, non-terminal
  status) instead of the hardcoded `"open"`. Default behavior is
  unchanged — default `:statuses` puts `"open"` first. Projects
  that customize statuses (e.g. `["todo" "active" "done"]`) get
  intake at `"todo"` automatically; no separate config key needed.

- The agent preamble emitted by `knot prime` for AFK mode is now
  selected via a new `:afk-mode` config key (default `"afk"`)
  instead of the hardcoded literal `"afk"`. Projects that rename
  the AFK mode (e.g. `:modes ["solo" "team"]` with
  `:afk-mode "solo"`) get the preamble on the renamed mode;
  `:afk-mode nil` opts out entirely. `knot info` surfaces the
  effective value under `allowed_values.afk_mode`; the `init`
  stub writes a documented `:afk-mode "afk"` line.

### Changed (BREAKING)

- All `--json` read commands now wrap their output in a tagged envelope
  `{schema_version: 1, ok: true, data: <payload>}` instead of returning a
  bare object/array. `knot list/ready/blocked/closed --json` change from
  `[ ... ]` to `{"schema_version": 1, "ok": true, "data": [ ... ]}`;
  `knot show --json`, `knot dep tree --json`, and `knot prime --json`
  change from `{ ... }` to `{"schema_version": 1, "ok": true,
  "data": { ... }}`. The `data` payload is unchanged from prior shapes.
- Acceptance criteria are now structured frontmatter
  (`acceptance: [{title, done}]`) instead of freeform `- [ ]`
  checkboxes in the body. The on-disk file format changes
  accordingly: the `## Acceptance Criteria` section is **never
  stored** on disk — `knot show` synthesizes it from frontmatter at
  display time, alongside the inverse sections. A one-shot
  `knot migrate-ac` command (hidden from top-level help) lifts every
  body's existing `## Acceptance Criteria` checklist into structured
  frontmatter and strips the section; idempotent on already-migrated
  tickets, safe to re-run. Existing acceptance bullets in body
  content will not appear in `knot show` until migrated.
- The v0.3 envelope contract is **extended**: `knot check --json` is the
  first command where `ok` mirrors a *health verdict*, so `ok: false`
  may now coexist with a `data` slot when errors are present
  (`{schema_version: 1, ok: false, data: {issues: [...], scanned:
  {...}}}`). The earlier rule (`ok: false` ↔ `error` slot, no `data`)
  still holds for the cannot-scan case (exit 2). Argument-parse errors
  for `knot check` stay on stderr with exit 2 in both modes — matches
  the arg-parsing-stays-on-stderr policy.
- The active (in-progress) status is now derived from the project's
  `:active-status` config key instead of the hardcoded string
  `"in_progress"`. Default behavior is unchanged because
  `:active-status` defaults to `"in_progress"`. Projects that
  customize `:statuses` (e.g. `["open" "review" "shipped"]`) can now
  define their own active status; `knot start`, `knot ready`'s
  blocked-ness check, and the ls-table render colors all read from
  the same source of truth. `knot check` validates `:active-status`
  via the `invalid_active_status` issue code.

### Removed

- `knot create` no longer accepts the `--afk` and `--hitl` shortcut
  flags. `--mode <value>` is the only path to set the mode at create
  time. The shortcuts baked the canonical mode names `"afk"` and
  `"hitl"` into CLI parsing — projects that customize `:modes` (e.g.
  `["solo" "team"]`) would expose shortcuts referencing modes they did
  not have. `knot create` is also now a strict-parsing command:
  unknown flags (`--afk`, `--hitl`, `--body`, anything mistyped) exit
  non-zero with `knot: Unknown option: :<name>` on stderr, matching
  the behavior already in place on
  `prime`/`ready`/`blocked`/`closed`. The init stub documents the
  per-mode-shortcut invariant under `:modes` for future contributors.
  Pre-1.0 break window — no deprecation cycle. Migration: replace
  `--afk` with `--mode afk` and `--hitl` with `--mode hitl`.
- `knot dep cycle` is removed; its role is subsumed by `knot check
  --code dep_cycle`. The semantic shift: `dep cycle` previously scanned
  only non-terminal tickets, while `knot check` scans the whole project
  (live + archive). Cycles among archived tickets now surface as issues
  — they are real data-integrity problems if a ticket is later
  reopened.

### Fixed

- README, `.claude/skills/knot/SKILL.md`, and
  `.claude/skills/knot/references/json-protocol.md` document the
  `prime --json` stale-flag asymmetry: `stale: true` appears only on
  `in_progress` entries, never on the `ready` copy of the same ticket.
  Code behavior unchanged — narrow JSON surface preserved on purpose.
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

- Cross-platform stability: `--json` envelopes emit POSIX-normalized
  paths on every OS — `meta.archived_to` (close/terminal-status) and
  `info --json`'s `paths.*` (cwd, project_root, config_path,
  tickets_dir, tickets_path, archive_path) all flow through
  `babashka.fs/unixify`, so JSON consumers don't have to branch on
  `os.name`. Stdout paths stay native (humans copy-paste them into
  the local shell). Stdout also uses `\n` line endings on every
  platform — Babashka's Windows JVM was emitting `\r\n` via `println`,
  surprising scripts that compared exact stdout (e.g. `knot --version`
  output). Bundled skill (`SKILL.md`, `references/json-protocol.md`)
  and `docs/agents/testing.md` ("Cross-platform considerations")
  document the path-policy and test rules. CI now treats
  `windows-latest` as a blocking gate; `continue-on-error` was dropped
  from the matrix.

- `knot create` no longer produces colliding ticket ids when two
  tickets are minted in the same millisecond. Id generation now
  follows the standard ULID monotonic spec — same-ms (or
  backward-clock) calls reuse the prior timestamp and increment
  the random component by 1; on the (astronomically rare) overflow
  at 1024 same-ms tickets, the timestamp bumps by 1 and the random
  component resets. The atomic-create path uses `Files/write
  CREATE_NEW`, so even a millisecond collision that *did* slip
  through writes a fresh ticket via retry rather than overwriting
  an existing file. Eliminates the `prime-cmd-json-test` truncation
  flake (verified: 100/100 consecutive `bb test` runs green).

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
