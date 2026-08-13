# Changelog

All notable changes to Knot are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Versioning

- Tags are cut as `vX.Y.Z` (e.g. `v0.0.1`).
- The single source of truth for the current version is `src/knot/version.clj`, surfaced to users via `knot --version` and the `knot --help` banner.
- Version bumps are driven by the `/release` slash command.

## [Unreleased]

### Added/Changed/Fixed/Removed

### Changed

- **Agent-facing material is now placed by surface: pull, push, pointer (ADR 0017).**
  Knot reaches an agent through `knot --help` (pull — generated, cannot drift), `knot prime` (push — always loaded, every turn), and the bundled skill (pointer — loaded when its description fires).
  Material had accumulated on whichever surface it was written for first: the intent table lived on both push and pointer, the skill cached a command index and a filter inventory `--help` already generates, and `## Recently Closed` spent 38% of push's words on mid-sentence-truncated summaries.
  Each piece now lives on exactly one surface, placed by which failure it prevents — corpus corruption first, wrong-flag last, because unknown flags are rejected loudly and self-correct.
  `knot prime`'s hitl preamble drops to the three intents that are irrecoverable (`ready`, `show`, `close --summary`) and points at `--help` for the rest; `## Recently Closed` renders `id  title` only.
  `--json` still carries full summaries — JSON consumers are not a context surface.

### Added

- **`knot <cmd> --help` gains a `NOTES` section** for the per-command caveats a flag description can't hold.
  `knot help edit` is the first entry: it needs a TTY, so a CI job or an agent run reaches for `knot update` or `knot add-note` instead.
  This is where any gotcha currently cached in prose belongs.

### Fixed

- **`check --json` issue paths now use forward slashes on Windows.**
  `data.issues[].path` was the last `--json` path field emitting native separators.
  The form of every JSON path is now a stated contract: absolute and POSIX-separated at all four sites, because knot addresses tickets by id and only locates files by path (ADR 0016).
  `json-protocol.md` gains a *Path fields* section, `data.issues[].path` is documented for the first time (including that only file-level codes carry it), and `json_contract_test.clj` pins `fs/absolute?` at every site.
  `info`'s `paths.tickets_dir` stays relative by design: it echoes the config value rather than locating a file.
  No shape change, so `schema_version` stays at `1`.

- **`update <id> --status <terminal> --json` now emits `meta.archived_to`.**
  It performed the same live to archive routing as `close` and `status`, but reported nothing, so JSON consumers tracking file locations lost the file on that call.
  `meta.archived_to` is now specified as a location report ("this ticket is in the archive, here"), emitted by `close`, `status`, and `update` whenever the resulting status is terminal, whether or not the call moved a file.
  An absent `meta` means the live directory.
  The un-archiving direction stays silent: `data.status` already carries that fact.
  Because the rule keys on resulting status rather than on flags, a field-only `update` on an archived ticket reports too.
  A shared `archive-meta` helper in `knot.cli` computes the slot for every transition path, so reporting cannot drift from routing again.
  Purely additive; `schema_version` stays at `1`.
  See ADR 0015 and `json_contract_test.clj`.

## [0.9.0] - 2026-06-24

### Added

- **`CC` column and `cc` JSON on `list` / `ready` / `blocked`.**
  A leading column marks which connected component each ticket belongs to in the live-induced graph (`:parent` ∪ `:deps` ∪ `:links`, undirected, closed tickets non-conductive, same scope as `LEV`/`CPL`, ADRs 0011/0012).
  Ordinals run size-descending over all live multi-member components (largest = 1), ties broken by min member id, numbered globally and independently of filters; singletons render `-`.
  The column appears only when a visible row sits in a multi-member component.
  `--json` emits `cc` on every row (integer ordinal, `null` for singletons); `show`, `closed`, and mutator outputs stay byte-unchanged.
  A new `knot.query` connected-components primitive backs it.
  See ADR 0013.
- **`--component <id>` on `list` / `ready` / `blocked`.**
  Isolates the single live-induced component containing the seed (all axes, undirected, closed non-conductive).
  Single id only, never an ordinal, and a closed seed fails fast.
  No `--via`, and mutually exclusive with `--closure`.
  Composes with `--tag` / `--type` / `--mode` / `--limit`; membership is computed over the full live corpus, independent of filters.
  Absent from `closed`: the archive has no live components.
  See ADR 0014 for how it differs from `--closure`.
- **`knot.el`: `CC` column and a `--component` filter.**
  The Emacs client tracks the CLI: an always-on leading `CC` column in `knot-list` (`-` for singletons) and a prompt-less single-seed component filter (cursor or lone mark; more than one mark refuses).
  The filter transient binds `c` to component and `C` to closure, each arm clearing the other; `C-u` clears, and `c` is a no-op with a message in the closed view.

## [0.8.0] - 2026-06-21

### Added

- **`LEV` + `CPL` columns and `leverage` / `coupling` JSON on `list` / `ready` / `blocked`.**
  Two live-induced graph metrics now sit beside each row.
  `LEV` (leverage, ADR 0011) is the size of the ticket's transitive reverse-`:deps` cone over live tickets, so it counts the open tickets that would be unblocked downstream.
  It is cycle-guarded, closed nodes sever the cone, broken refs drop, and the row itself is excluded.
  `CPL` (coupling, ADR 0012) is the 1-hop undirected degree: distinct live neighbors reached via `:deps` (both directions) or `:links`, deduped across axes, `:parent` excluded, closed neighbors dropped.
  Both render as integer columns between `AC` and `TITLE`, and as `leverage` / `coupling` integers under `--json`.
  New `knot.query` primitives back both metrics.
- **`--closure <id>[,<id>…]` on `list` / `ready` / `blocked` / `closed`.**
  Filters to the undirected transitive closure of the seeds over `:parent`, `:deps`, and `:links`.
  Multi-seed is a union (comma-separated or repeatable); `--via parent,deps,links` narrows the axes.
  Seeds resolve like `--parent` (partial ids; `not_found` / `ambiguous_id` envelopes under `--json`).
  The closure covers the full corpus, archive included; each command's display filter still governs what shows, and the flag composes with every other filter.
  Output is plain: no extra columns or JSON fields.
  See ADR 0010.
- **`knot.el`: `LEV` / `CPL` columns and a closure filter.**
  The Emacs client tracks the CLI: `LEV` and `CPL` columns in `knot-list`, and a closure arm in the filter transient that scopes the view to the closure of the marked tickets (or the ticket at point), mapping to `--closure` and composing with every other filter across `list` / `ready` / `blocked`.

## [0.7.0] - 2026-06-19

### Added

- **`knot delete <id>`: leaf-only file removal across live + archive.**
  Cleans up typo'd tickets, AI-generated duplicates, and archive noise.
  Strict-resolves the id, scans every other ticket (live + archive) for incoming `:parent`/`:deps`/`:links` references, then either unlinks the file (stdout = removed path) or refuses (exit 1; stderr enumerates each referrer and field).
  Under `--json`, success emits `{ok:true, data:{deleted:{id,path}, cleaned:[]}}`; refusal emits the new `has_incoming_refs` envelope with a `referrers: {id, field}[]` payload sorted by id.
  Not-found and ambiguous-id failures follow the standard envelopes.
  Bare `delete` doubles as the dry-run for `--cascade`.
  See ADR-0008; the bundled `.claude/skills/knot` ships the new command and code in the same release.
- **`knot delete <id> --cascade`: reference cleanup path.**
  Performs the rewrite that bare `delete` refuses: every referrer (live + archive) drops the target from `:deps`/`:links` and dissocs its `:parent`, dropping the field key when its list empties (mirrors `undep` / `unlink`).
  Each cleaned referrer's `:updated` bumps.
  Write order is referrers first (alphabetical by id), target last; a mid-batch save failure leaves a stderr breadcrumb naming the committed referrers and leaves the target in place, so re-running is idempotent.
  Stderr emits one `knot delete: cleaned <id> (:field, ...)` line per referrer; stdout stays a single line.
  Under `--json`, `data.cleaned` carries `[{id, fields:[...]}]`, sorted by id.
  `--cascade` on a leaf is a silent no-op.
  Pinned by ADR-0008; bundled skill and json-protocol reference updated in the same release.
- **`--parent <id>` filter on `list` / `ready` / `blocked` / `closed`.**
  Surfaces a parent's direct children.
  Repeatable (values OR together), matched on flat `:parent` equality after resolution: no recursion, no orphan sentinel.
  Each value resolves through standard partial-id resolution (live + archive) before projection, the only list filter whose values resolve, so `closed --parent` can reach an archived parent.
  An unresolvable value fails loudly: a stderr die in text mode, or the standard `not_found` / `ambiguous_id` envelope under `--json`.
  `prime` is out of scope.
  Bundled skill updated in the same release.
- **Umbrella progress rollup in listings and `show` (CHLD column).**
  An umbrella's terminal/total tally over its direct children now surfaces across the four listing commands and `show`, per ADR-0009.
  `query/children-progress` returns `[terminal total]`, reusing `terminal-statuses`, so Won't-do closures count.
  A `CHLD` column (`t/total`, `-` for non-umbrellas) splices into the `ls` table only when the result set holds at least one umbrella, shared across `list` / `ready` / `blocked` / `closed` over the full live + archive corpus.
  `show` gains a `## Children (t/total)` heading.
  `show --json` and `list --json` emit `children_total` / `children_terminal` on umbrella rows only, so their absence doubles as the umbrella predicate.
  Bundled skill and json-protocol reference updated in the same release.
- **`knot.el`: umbrella rollup, `--parent` filter, and editor ergonomics.**
  The Emacs client tracks the CLI: an always-on `S`-sortable `CHLD` column and a `Children (t/total)` heading on umbrella `show` buffers; a `--parent` arm in the filter transient (`,P`) that offers umbrella-only candidates and pre-fills the umbrella id at point; a `knot-delete` (`K`) command with bare and `--cascade` flows; a ticket count in the `knot-list` header-line; per-git-worktree buffer names for concurrent checkouts; and strikethrough on terminal deps/links in the `show` top-line.

### Fixed

- **Windows: canonicalize cwd in `discover-ctx`; platform separator in the delete archive test.**
  `discover-ctx` fell back to raw `(fs/cwd)` when `.tickets/` was absent, while `config/discover` canonicalizes the start-dir once a marker exists.
  On Windows, `Path.toRealPath()` resolves 8.3 short names (`RUNNER~1`) to long names (`runneradmin`), so `knot create` (no marker) and `knot delete` (marker) returned different strings for the same file.
  The fallback is now canonicalized so both agree.
  The `delete-end-to-end-test` archive assertion uses `fs/file-separator` instead of a hard-coded POSIX `archive/`, so it matches `archive\` on Windows.

## [0.6.0] - 2026-05-19

### Added

- **`knot serve`: a read-only browser panel for the current project.**
  Boots an http-kit server on loopback (default `127.0.0.1:7777`; `--port 0` for an ephemeral port, `--no-open` / `--open` to control the browser launch, `--dev` to read UI assets from disk instead of the classpath).
  Every `/api/*` route shells out to `knot ... --json` and forwards the envelope verbatim, behind an origin allowlist accepting only `nil` / `"null"` / `http://(127.0.0.1|localhost):<port>`.
  A per-project heartbeat at `${TMPDIR}/knot-serve-<sha256(project-root)[:12]>.json` makes a second `knot serve` in the same project detect the running instance and exit `0` instead of binding twice.
  UI assets moved from `prototype/serve/public/` to `resources/knot/serve/public/`; `prototype/` is gone.
  See ADR-0005 (stack layout), ADR-0006 (read-only v1), and ADR-0007 (shell-out per request). http-kit is lazy-required inside the handler, so every other command stays cheap.
- **Per-value colors for `:type`, `:priority`, `:mode` in list tables.**
  `knot ls / ready / closed / blocked` now hue-code these three columns the way `:status` already did.
  `:type` maps directly (`feature` / `bug` / `task` / `chore` / `epic` each get a distinguishable hue); `:priority` graduates from red-bold at 0 through to faint at 4; `:mode` is role-derived through a new public `knot.output/mode-role` helper, where the configured `:afk-mode` gets the highlighted role, the default mode renders plain, and anything else is faint (`:afk` resolves to nothing when `:afk-mode` is `nil`).
  No new config surface; custom types and modes degrade to faint.
- **Dash-leading-safe values for every value-bearing string flag.**
  Generalises the body-flag extract pattern into a registry-driven walk over every value-bearing string flag across `create` / `update` / `status` / `close` / the list family / `prime` / `init` / `check`.
  Values starting with `-` (`--acceptance "- text"`, `--summary "-cancelled"`, `--tags "-foo"`, `--remove-tag "-foo"`, `--dep "-bogus"`, `--link "-bogus"`) survive babashka.cli's `parse-key` instead of failing with "Unknown option".
  Per-command extract maps derive from `knot.help/registry` rather than hand-maintenance.
  The v0.5.0 dash-leading hint copy is trimmed; the hint function stays as a defensive fallback.
  Tests walk all four argv shapes (`"- text"`, `"--text"`, `"-x"`, multi-line bullet list) across the full flag surface, including the `--add-*` / `--remove-*` and `--dep` / `--link` paths that flow through `extract-rel-order`.
- **`knot.el`: status cells colored by role, not literal name.**
  `knot-format-status` drives face choice off `knot info --json`'s `allowed_values`: the configured `:active-status` gets `knot-status-active`, anything in `:terminal-statuses` gets `knot-status-terminal`, everything else falls through to `knot-status-open`.
  Custom status sets (e.g. `:statuses ["open" "active" "closed"]` with `:active-status "active"`) now render with correct faces, where previously the `pcase` was hardcoded against `"open"` / `"in_progress"` / `"closed"` and left them unpropertized.
  Faces are renamed to match the roles the code checks; no back-compat aliases.

## [0.5.0] - 2026-05-17

### Added

- **`AGE` column on `knot list / ready / blocked / closed`.**
  Every listing table carries an `AGE` cell immediately left of `AC` (or `TITLE` when no `AC` column shows), computed from each ticket's `:updated` against `now` with the bucketing prime's In Progress section already uses (`Nd` < 14d, `Nw` 14 to 42d, `Nm` > 42d, `-` when missing or unparseable).
  The per-ticket day count (formerly `:prime-age-days`) is now `:age-days`, fed by one shared helper across all five listing pipelines.
  JSON is unchanged: `:updated` remains the sole age-related field, no new keys, no `schema_version` bump.
- **`Age` column in the `emacs/knot.el` list buffer.**
  Splices between `Assignee` and `AC`.
  The bucketed string is computed client-side from the JSON `:updated` field, so the rendering matches the CLI exactly.
  Column-header sort (`S`) on `Age` aliases to the `updated` sort key, so the buffer sorts by ISO timestamp rather than the rendered string.
- **`--priority N` filter on listing commands.**
  `knot list`, `ready`, `blocked`, `closed`, and `prime` accept `--priority` (repeatable, integer 0..4), so callers can scope by priority: `knot ready --priority 0` for top-priority work, or `--priority 0 --priority 1` for the high band.
  Out-of-range values are rejected at parse time with a clear message.
  Unlike the open enums (`--type`, `--status`), priority is a closed range across config and check.
- **JSON Schema for knot tickets.**
  A top-level `knot.schema.json` at the repo root, plus a `knot schema` command that emits it to stdout.
  Editors and CI can validate ticket frontmatter with standard JSON-Schema tooling.
  The bundled skill points at the schema as the canonical frontmatter contract.
- **Release-tag smoke CI workflow.**
  `.github/workflows/release-smoke.yml` fires on every `v*` tag push (and `workflow_dispatch`) across ubuntu-, macos-, and windows-latest.
  It pins babashka and bbin at the top level via `env:`, installs knot via `bbin install`, then exercises the golden-path lifecycle (`init` → `create` → `ls` → `show` → `start` → `close`) along with an `ls --json` envelope assertion and a `--version` tag-equality check.
  Install regressions surface inside the release pipeline rather than via user reports.
- **Tags transient in `knot.el`** (`T` prefix in list and show buffers).
  Three suffixes: `a` adds tags, `r` removes tags, `T` replaces the whole list.
  Add and remove use `completing-read-multiple` over the project tag union; free input still works for brand-new tags.
  Honors `knot-list--marks` for bulk fan-out, and `+` / `-` on the `tags:` line in the show buffer route to add and remove.
- **Dired-style marks on `knot.el` list buffers** (`m` mark, `u` unmark, `U` unmark all).
  Most mutating actions honor the mark set: `,` (update) and the per-field suffixes (status / priority / mode / type / tags / assignee / parent) fan out across every marked id rather than the row at point.
  The header displays `[N marked]` right-aligned via inline `:align-to`.
- **`knot-find-id-at-point` and kill-on-quit in `knot.el` show buffers.**
  `q` kills the show buffer rather than burying it, so transient show buffers stop accumulating, and `knot-find-id-at-point` makes `RET` on a buttonized id navigate to that ticket.
- **Section-aware `+` and general `-` in `knot.el` show buffers.**
  `+` on the deps / links / acceptance / tags sections adds an entry of that kind; `-` on any removable line removes it.
  Field detection rides on the `'knot-field` text property already in place for `RET`-on-AC.
- **Polish in `knot.el`.**
  `y-or-n-p` for destructive prompts, `revert-buffer` rebound to `g`, `pop-to-buffer-same-window` so dedicated buffers don't split frames, `which-key` integration on the transients, `visual-wrap-prefix-mode` in show / capture / deps buffers, `pixel-scroll-precision-mode` in list / deps buffers, and status-glyph plus priority/type annotations on `completing-read` pickers.
- **Tag prompts complete over the project tag union in `knot.el`.**
  All three tag-input sites (`--tag` filter, replace, create transient) use `completing-read` / `completing-read-multiple` against the live project tag union via a shared `knot--all-project-tags` helper.
  Free input is preserved, so new tag names still work.

### Changed

- **Open-children gate on parent status transitions.**
  `knot start`, `knot close`, `knot status <id> <target>`, and `knot update --status <target>` block when the umbrella has any child in a non-terminal status.
  The gate fires in both directions: `*→active` and `active→terminal`.
  It is structurally parallel to the v0.4 AC gate.
  Stderr enumerates the open child ids; `--json` returns `{ok:false, error:{code:"open_children", message, open_children:[...]}}` on stdout with exit 1.
  `--force` overrides; on the close side it requires `--summary "<reason>"` (appended as a Notes entry), while the start side accepts bare `--force`, because starting a parent is provisional.
  When both the AC and open-children gates would fire, one `--force` bypasses both and stderr emits one warning per gate.
  Closing or starting a parent whose children are all terminal, or that has no children, succeeds without the gate firing.
  See [ADR-0003](docs/adr/0003-parent-children-gate-status-transitions-not-readiness.md) for the rationale: gate transitions, not readiness.
- **`knot.el` baseline bumped to Emacs 30.1.**
  `Package-Requires` rises from `(emacs "28.1")` to `(emacs "30.1")`.
  Around 53 non-evil keymap construction sites move from `(define-key map (kbd "K") #'fn)` to `(keymap-set map "K"
  #'fn)` across the `knot-info`, `knot-list`, `knot-show`, `knot-capture`, and
  `knot-deps` mode-maps.
  Evil compat switches to `keymap-unset` (parent-shadow semantics preserved by omitting `REMOVE`); `evil-define-key*` keeps `kbd` at its scoped call site.
  The README's global-bind example modernizes to `keymap-global-set`.
  Users on Emacs < 30.1 should pin `knot.el` to the v0.4.0 tag.

### Fixed

- **`/release` slash command: Markdown headings stripped from the tag message.**
  Step 8 invoked `git tag -a vX.Y.Z -F release-notes-…txt` with git's default cleanup mode, which strips `#` lines as comments and silently deleted every `## Highlights` / `## Upgrade path` heading from the annotated tag.
  It now passes `--cleanup=verbatim` and verifies the heading count via `git cat-file -p`.
  Step 12 also switches the coord-ticket lookup from `--status open` to a jq `select(.status != "closed")` filter, so an `in_progress` coord (the convention here, since the coord is `knot start`ed at release-cut time) is found rather than skipped.

- **`release-smoke` Windows step exited with `bbin: command not found`.**
  The "Install bbin" step downloads bbin into `$HOME/.local/bin`, appends that directory to `$GITHUB_PATH`, then runs `bbin --version` in the same step.
  `$GITHUB_PATH` only takes effect in subsequent steps, so the in-step lookup relied on `~/.local/bin` already being on `$PATH`, which holds on Linux and macOS but not on the Windows runner.
  Verification now invokes the full path (`"$HOME/.local/bin/bbin" --version`); later steps still resolve `bbin` via `$GITHUB_PATH`.

- **Windows CI on the `bb test` runner.**
  Two failures surfaced after v0.5.0's JSON Schema work landed:
  - `every-real-ticket-validates` failed against every ticket with "missing required property `id`/`title`".
    `knot.ticket/parse` matches the literal fence strings `"---\n"` and `"\n---\n"`, so a Windows checkout under `core.autocrlf=true` made every ticket look bodyless and yielded empty frontmatter.
  - `checked-in-schema-is-in-sync` reported `knot.schema.json` out of sync.
    Jackson's pretty printer uses `System.lineSeparator()`, so `schema-json` emitted `\r\n` between content lines on the Windows JVM while the checked-in file was LF.
    A new `.gitattributes` with `* text=auto eol=lf` pins every text file to LF in the working tree across Linux, macOS, and Windows, and `knot.schema/schema-json` post-processes the Jackson output via `str/replace "\r\n" "\n"` so its return value is identical everywhere.
    Either fix alone closes the failure; together they keep the byte-compare contract robust to future tooling drift.

- **AC gate hint pointed at a non-existent `--check` flag, and the unchecked count was inverted.**
  The hint now points at the real `knot update <id> --ac "<title>" --done` triple, and the count uses `(count open)` directly, so the "N of M unchecked" line matches the open-AC list.
  The hint also moves out of the ex-info message into the plain-text printer, keeping `error.message` in the JSON envelope a bare diagnosis while `open_acceptance` carries the action data.
- **`knot create`: a missing value for a numeric flag now names the offending flag.**
  A value-bearing numeric flag such as `--priority` given no value made babashka.cli bind it to implicit boolean `true`; the subsequent `:coerce :long` then failed with `Coerce failure: cannot transform (implicit) true to long`, never naming the flag.
  The top-level catch now detects bb-cli's missing-value signature (`:cause :coerce` plus the `(implicit) ` substring) and reads `:option` / `:spec` from ex-data to name the flag and its required coerce type.
  Covers every `:coerce :long` flag in the registry (`--priority` on `create`/`update`; `--limit` on the list family).
  Genuine unparseable-value cases (`--priority abc`) surface the original error untouched.
- **`bb lint:elisp` works under `-Q` with a system-wide elpa.**
  The task no longer assumes a user `~/.emacs.d/elpa` and resolves `package-lint` from the system load-path, so CI and fresh machines lint clean without per-user package state.

## [0.4.0] - 2026-05-13

### Added

- **New Emacs UI: `emacs/knot.el`.**
  A single-file magit-style mode fronting the `knot` CLI.
  Project detection mirrors `magit-toplevel` via cached `knot info --json`.
  `M-x knot` opens a dispatch transient.
  Every CLI subprocess goes through one boundary function (`knot-cli-call`) that parses `--json` and surfaces `ok:false` envelopes as `user-error` in the minibuffer.
  Buffer names are project-qualified throughout (`*knot-list: <project>*`, `*knot-show: <project> · <id>*`), so the mode is multi-project safe.
  A startup `lwarn` fires when the on-disk `knot` is older than `knot-minimum-cli-version` (currently 0.3.0).
  A new `bb lint:elisp` task byte-compiles `emacs/knot.el` and runs `package-lint`.
  - **List buffer** (`knot-list-mode`): one project-scoped `tabulated-list` buffer with `l`/`r`/`b`/`c` view switching across list/ready/blocked/closed, a filter transient on `f`, and a sort transient on `o` with single-key suffixes (id/title/priority/status/type/mode/created/updated, `d` toggles direction, `R` resets to the view default).
    Per-view default orderings (list/ready/blocked → priority asc with id tiebreak; closed → updated desc) hydrate when unset.
    Sort is client-side over the buffered rows, so toggling never re-hits the CLI.
    `,` (or `M` under `knot-evil-mode`) opens the update transient on the row at point.
  - **Show buffer** (`knot-show-mode`): `markdown-view-mode` rendering with buttonized ids.
    `RET` on an AC flips done/undone, and `RET` on any other editable frontmatter field (status/type/priority/mode/assignee/parent/tags) opens the matching update prompt.
    Dep and link rows support remove-at-point.
  - **Create transient** (capital-`C` quick-create plus a full transient with per-flag readers) lands post-create in the show buffer on `## Description`.
    Deps and links autocomplete against live and archived tickets.
  - **Update transient** covers status/priority/mode/type/tags/assignee/parent inline with no buffer pops, plus a Long-form group routing description/design/body/note edits to capture buffers.
  - **Capture buffers** for long-form fields commit via `C-c C-c` (`knot update --description|--design|--body` or `knot add-note`) and discard via `C-c C-k`.
    Capital-`E` escapes to `knot edit` via emacsclient.
  - **Deps tree buffer** (`knot-deps-mode`) renders `knot dep tree --json` with status glyphs and buttonized nodes.
  - **Cross-buffer refresh** propagates mutations across sibling list/show/deps buffers in the same project, preserving point on the originating row id.

- **Evil / Doom support: `knot-evil-mode`** is a `:global t` opt-in minor mode wrapping a soft `(require 'evil nil t)`.
  Enabling it destructively rewires the `knot-list` / `knot-show` / `knot-info` / `knot-deps` mode-maps via `evil-define-key*` in each mode's normal-state auxiliary keymap, and sets normal initial state on the read-only modes (insert on capture; `C-c C-c` and `C-c C-k` commit and discard regardless of state).
  Setup is data-driven through `knot-evil--stock-keys` / `knot-evil--bindings` / `knot-evil--initial-states`, and idempotent.
  `evil` is a soft dependency: the package loads without it, and toggling the mode on raises `user-error` when evil is missing.
  `emacs/README.md` documents install, the full binding table, and a paste-ready Doom `use-package!` + `map! :localleader` snippet.

- `knot create` now **hints when a value starting with `-` is mistaken for a flag** by babashka.cli.
  Detection rides on the spec-shape signature: a flag listed in the `:spec` lands in `:opts` as implicit `true` / `[true]` despite not being `:coerce :boolean`.
  The cryptic `Unknown option: :e` / `Unknown option: :test` message becomes an actionable hint pointing at the `--<flag>=<value>` form and the broader pre-extract follow-up ([kno-01kr0129m0y9](.tickets/kno-01kr0129m0y9--pre-extract-dash-leading-safe-handling-for-value.md)).
  Genuine unknown-flag errors are unaffected.

- `knot check` emits a new `legacy_acceptance_section` **warning** (severity `warning`, not `error`) when a ticket body still carries a `## Acceptance Criteria` heading after the v0.3 frontmatter migration.
  Filterable by `--code legacy_acceptance_section` and `--severity warning`.
  It self-cleans: the warning disappears after `knot migrate-ac` lifts the section into structured frontmatter.
  Closes the silent half-broken state that v0.2 → v0.3 upgraders hit when they skipped the migration, whose only prior discovery path was reading the CHANGELOG.

- Acceptance criteria are now **load-bearing on terminal transitions**.
  `knot close`, `knot status <id> <terminal>`, and `knot update <id> --status <terminal>` enforce a v0.3 acceptance gate: when the source status is `:active-status` and any frontmatter `:acceptance` entry has `done: false`, the transition is blocked.
  Stderr lists the unchecked count, the indented open titles, and the `--check` / `--force --summary` hint.
  JSON carries the new error code `acceptance_incomplete` with `error.open_acceptance: [{title}, ...]` on stdout.
  Exit 1.
- `--force` flag on `knot close`, `knot status`, and `knot update` bypasses the gate when paired with a non-blank `--summary`.
  The summary is appended as a Notes entry and serves as the override record.
  `--force` without `--summary` (or with a blank one) fails `invalid_argument`.
  When the gate would not fire, `--force` is a silent no-op.
- `knot update` gains `--status <new>` and `--summary <text>`.
  AC mutations (`--ac --done`, `--add-ac`, `--remove-ac`) apply before the gate, so `knot update <id> --ac "last AC" --done --status closed` checks then closes in one disk write.
- `knot.acceptance` exposes three pure predicates used by the gate and by listing-side AC progress: `complete?`, `progress` (returns `[done total]`), and `open-titles`.
- **AC progress is now visible in listing tables.**
  `knot ls`, `ready`, `blocked`, and `closed` gain a conditional `AC` column (rendered as `d/t`, e.g. `2/5`) immediately before `TITLE`.
  Header and column are omitted entirely when no ticket in the result set carries `:acceptance`, so quiet projects don't pay the width cost.
  Tickets without AC render as `-`.
  Force-closed tickets render their partial counts (`2/5`) as an audit signal.
  `ls --json` is unchanged: raw `:acceptance` already passes through.
- **AC progress is now visible in `knot prime`.**
  The In Progress and Ready row shapes gain a conditional AC slot before `title` (7 cols for In Progress, 6 for Ready, when any ticket in the section has AC; unchanged otherwise).
  The renderer is whitespace-only, so the shape holds for AI agents and downstream tools.
- **New `## Ready to close` section in `knot prime`.**
  Renders between `## In Progress` and `## Ready`, surfacing active-status tickets whose every acceptance entry is checked: the call-to-action that pairs with the close-gate.
  It uses the In Progress line shape with the age column, sorts by `:updated` descending, is uncapped, and is omitted when empty.
  The HITL nudge reads "All acceptance criteria are checked — close with `knot close <id> --summary "..."`."; the AFK nudge reads "Close these before grabbing new tickets."
  `prime-cmd` partitions active tickets so a ticket appears in either `:ready-to-close` or `:in-progress`, never both.
- **`prime --json`** gains a `data.ready_to_close` array parallel to `in_progress`, `ready`, and `recently_closed`, using the same body-less compact ticket projection.
  Per-ticket projections carry no derived `acceptance_progress` field: JSON consumers needing the raw AC list use `ls --json` or `show --json`.
- `knot.query/ready-to-close?` predicate: `(and (= status active-status) (seq ac) (acceptance/complete? ac))`.
  Vacuously-complete tickets (no AC list) deliberately do not migrate; only an explicit fully-checked checklist counts.

### Changed

- `knot close` no longer succeeds unconditionally on an active ticket with unchecked acceptance criteria.
  This is an intentional behavior break: prior to v0.3 the criteria were stored but never enforced.
  Projects with multi-terminal configs (e.g. `:terminal-statuses #{"closed" "wontfix"}`) hit the gate on `in_progress → wontfix` too; the documented escape hatch is `--force --summary "wontfix: <why>"`, where the summary becomes the abandonment record.
- `knot prime` directive content overhauled against v0.3's CLI surface:
  - **HITL preamble** swaps the old 7-row "When the user says..." table for an 8-row mapping that adds the agent-write verb (`knot update`) and an explicit `show`/`list`/`dep tree` row, with inline filter annotations on the read-row (`--mode afk`, `--tag`).
    The closing pointer now reads "For less-common ops (`info` / `check` / `link` / `reopen` / `--json` shapes / partial-id contract), invoke the `knot` skill", framing the skill as the long-tail reference rather than duplicating it.
  - **AFK preamble** inserts a `knot update <id>` step between `add-note` and `close`, tagged with a "never use `knot edit`, it opens $EDITOR and will fail without a TTY" anti-pattern.
    The skill pointer trims `lifecycle`, subsumed by the explicit verbs in the autonomous-flow checklist.
  - **`## Commands` cheatsheet retired entirely.**
    The preamble's intent table plus the bundled `knot` skill cover the same ground without per-session token cost.
    `prime --mode afk` agents benefit most: the cheatsheet was redundant with the autonomous-flow checklist.
  - **In Progress row format** is now `id  type  mode  pri  age  title` (6 cols).
    The `age` column renders the relative `:updated` delta as `Nd` (<14d), `Nw` (14d to 6w, floor by 7), or `Nm` (>6w, floor by 30); a missing `:updated` renders `-`.
    The binary `[stale]` prefix is retired, since the age column carries the staleness signal in readable form.
    The `prime --json` `stale: true` flag on `data.in_progress[]` is preserved (set when `:updated` >= 14d); the text/JSON asymmetry is intentional and documented.
  - **Ready row format** is now `id  type  mode  pri  title` (5 cols), with `type` inserted between `id` and `mode`.
    Missing fields render `-`.
  - **Recently Closed summaries** truncate at the first paragraph boundary (`\n\n`), with a 280-char hard cap on long single-paragraph summaries.
    When truncation fires, the line ends with ` (see knot show <id>)` so agents know where the rest lives.
    `prime --json data.recently_closed[].summary` keeps the full string.
  - **Per-section nudges** are now mode-conditioned.
    HITL is unchanged ("Resume here if the user picks up mid-stream." / "If asked 'what's next', recommend the top entry...").
    AFK drops the Ready nudge entirely, since the autonomous-flow checklist covers it, and rephrases the In Progress nudge to drop the "user" reference: "Finish your in-progress work before grabbing new tickets."
  - `prime --json` payload shape is **unchanged**: same keys on `data.in_progress[]` / `data.ready[]` / `data.recently_closed[]`, no `schema_version` bump, no new fields.
    The redesign is a display refresh; the contract is stable.

## [0.3.0] - 2026-05-06

### Added

- `knot create` gains repeatable `--dep <id>` and `--link <id>` flags to wire a new ticket into the graph at create time.
  `--dep` is lenient on missing targets (kept verbatim as a forward ref, matching `knot dep`'s tolerant-target contract); `--link` is strict, so every target must resolve uniquely or the command fails before any file is written.
  Both accept partial ids, dedupe equivalents that resolve to the same ticket, and may name archived targets, where a reciprocal `--link` write does not unarchive.
  `--dep X --link X` is allowed and records both relationships.
  When multiple strict inputs are bad, the first failure in left-to-right CLI order wins.
  A reciprocal-link write failure rolls back: applied recip links revert and the new ticket file is deleted.
  Plain text errors use the `knot create:` prefix; `--json` returns the standard `not_found` / `ambiguous_id` / `invalid_argument` envelope.
  Bundled skill kept in sync.

- New `test/knot/json_contract_test.clj` pins the v0.3 `--json` envelope contract for every read and mutating command at `bb test` time: schema_version + ok + data XOR error invariants asserted centrally; per-command `data` shape (key presence and types) asserted per command; the four ticket vector defaults (`tags`/`deps`/`links`/`external_refs`) always-array contract pinned on read and mutating envelopes; `meta.archived_to` pinned on `close --json` and any terminal `status --json` transition; and the four error envelopes, `not_found` (every id-resolving command), `ambiguous_id` with `candidates`, `cycle` with the path vector on `dep --json`, and the `check --json` exit-2 cannot-scan envelope.
  It also pins the documented asymmetries (`dep`/`undep`/`unlink` `to`-side soft resolution; `dep tree` tolerant unknown root; `knot check`'s `ok:false`-with-data exception).
  `knot.json-contract-test/with-tmp` is added to `.clj-kondo/config.edn`, so the lint baseline stays at 4 errors and 5 warnings, all pre-existing.

- `knot update` gains repeatable `--add-tag <t>` and `--remove-tag <t>` for per-tag deltas, complementing the whole-list `--tags <comma-list>`.
  They are mutually exclusive with `--tags` and with each other on the same value (overlap rejected as `invalid_argument`).
  Each is idempotent: adding a present tag or removing an absent one is a no-op.
  Existing order is preserved; removes drop in place; adds append at the end in flag order.
  An empty resulting set clears the `:tags` key, consistent with `--tags ""`.
  Values are trimmed; blank or comma-bearing values are rejected to preserve the round-trip invariant that any tag can be expressed via `--tags`.
- Acceptance criteria are now structured frontmatter (`acceptance: [{title, done}]`) instead of freeform `- [ ]` checkboxes in the body.
  The `## Acceptance Criteria` section is **never stored** on disk: `knot show` synthesizes it from frontmatter at display time, between the body and the inverse sections, exactly like `## Linked` / `## Blockers`.
  Single source of truth, no positional-index ambiguity.
  - `knot create --acceptance "<title>"` is now a repeatable string flag (modeled on `--external-ref`).
    Each occurrence appends one entry with `done: false`.
    The dash-prefixed body-flag pre-extraction no longer covers `--acceptance`, because criterion titles are short strings, not multi-line markdown.
    `--description` / `--design` are unchanged.
  - `knot update --ac "<title>" --done` (or `--undone`) flips a single frontmatter entry.
    The title must match exactly, case-sensitively.
    `--done` and `--undone` are mutually exclusive; `--ac` requires one of them, and each requires `--ac`.
    Adding and removing AC entries is deferred; use `knot edit` for now.
  - `knot list --acceptance-complete=false` (also on `ready`, `blocked`, `closed`) keeps only tickets with at least one undone AC.
    `=true` keeps tickets where every AC is done.
    Tickets with no `:acceptance` list are excluded from both filters: the dimension is completion of structured acceptance work, and absent ACs mean it does not apply.
  - `knot check` gains an `acceptance_invalid` validator catching malformed entries: non-list `:acceptance`, non-map entries, missing or non-string `:title`, missing or non-boolean `:done`.
    One issue per offending entry; the validator runs unconditionally.
  - **Migration**: a one-shot `knot migrate-ac` command (hidden from top-level `knot help`) lifts every body's `## Acceptance Criteria` section into structured frontmatter, then strips the section.
    Both checkbox bullets (`- [ ] / - [x] / - [X]`) and plain bullets (`- title`) are lifted; plain bullets default to `done: false`.
    Idempotent on migrated tickets, safe to re-run.

- New `knot info` command reports the project's effective runtime configuration and allowed values for agents, scripts, and humans.
  Five fixed sections: `Project` (knot version, name, prefix, config_present), `Paths` (cwd, project root, config path, tickets dir/path, archive path), `Defaults` (config-only `default_assignee` vs runtime `effective_create_assignee` with git fallback, `default_type`, `default_priority`, `default_mode`), `Allowed Values` (statuses, active_status, terminal_statuses ordered by the configured statuses order, types, modes, priority_range), and `Counts` (live, archive, total, over top-level `*.md` files only, no parsing).
  `--json` returns the v0.3 envelope; `--no-color` is accepted for consistency, since text is always plain.
  It tolerates malformed ticket files and counts via raw filesystem listing, but is strict on discovery: a missing project or invalid `.knot.edn` exits 1 with the reusable `no_project` / `config_invalid` envelopes under `--json`.
  `info` reports runtime facts, not health verdicts, so it stays on the ordinary 0/1 path; diagnostics, malformed-ticket reporting, and config-health checks remain `knot check`'s job.

- Uniform six-flag filter set across all listing commands.
  `list` gains `--limit`; `blocked` and `closed` gain `--status`, `--assignee`, `--tag`, `--type`, `--mode`; `prime` gains `--status`, `--assignee`, `--tag`, `--type`.
  On `prime`, filters apply across **all** sections (in_progress + ready + recently_closed), so `knot prime --assignee me` shows only your tickets everywhere.
  Empty filter results are valid empty arrays, not errors.

- New `knot update <id>` command for non-interactive ticket writes.
  Frontmatter flags (`--title`, `--type`, `--priority`, `--mode`, `--assignee`, `--parent`, `--tags`, `--external-ref`) set field values; a blank string (or an empty repeated `--external-ref` list) clears `:assignee` / `:parent` / `:tags` / `:external_refs`.
  Body flags `--description` and `--design` replace named sections in place.
  (Acceptance criteria are no longer body content under v0.3; flip a single AC's done state with `--ac "<title>" --done|--undone`.)
  `--body <text>` replaces the whole body and is destructive, with **no `--force` ceremony**: git is the documented undo path.
  `--body` is mutually exclusive with the sectional body flags.
  `--json` returns the v0.3 success envelope wrapping the post-mutation ticket under `:data`, with no `:meta` slot, since `update` never archives.
  `:updated` bumps on every successful save via `store/save!`.
  `--note` is intentionally absent: append remains `add-note`'s job, while `update` is purely set/replace.
  `edit` keeps its single meaning (open in `$EDITOR`); `update` is the non-interactive path agents and scripts use.

- New `knot check [<id>...]` command validates project integrity and surfaces issues.
  With no ids it scans every ticket (live + archive) and config; with ids it narrows the per-ticket tier to those, while globals always run on the full set.
  Initial check codes: `dep_cycle`, `unknown_id` (dangling `:deps`/`:links`/`:parent`), `invalid_status`, `invalid_type`, `invalid_mode`, `invalid_priority` (outside 0..4), `terminal_outside_archive` (bidirectional), `missing_required_field`, `frontmatter_parse_error`, `invalid_active_status`.
  The repeatable filter flags `--severity error|warning` and `--code <code>` OR within a flag and AND across flags; unknown severity is rejected at parse time, unknown code is silently accepted as an open enum.
  Filters apply before the exit-code decision, following grep semantics: the exit reflects the filtered view.
  Exit codes are 0 clean, 1 errors found, 2 unable to scan (no project root, invalid `.knot.edn`).
  Issues sort severity desc → code asc → first-id asc → message asc, identically in JSON and text.

- New top-level `Concurrency` section in the README explains the no-locking model, points at git as the conflict-detection and undo path, and links to the optimistic-concurrency placeholder ticket for projects that need multi-writer coordination later.

- New `.claude/skills/knot/references/json-protocol.md` is the canonical reference for the v0.3 `--json` envelope: envelope shape, the `ok` discriminator (with the `knot check` carve-out), the `meta` slot, schema versioning, the partial-id contract (strict vs soft resolution), the error-code catalogue (`not_found`, `ambiguous_id`, `cycle`, `invalid_argument`, `no_project`, `config_invalid`), per-command `data` shape tables (read and mutating), the `knot check` issue-code catalogue, and worked examples for each envelope variant.
  It lives under the bundled skill folder, so projects that copy the skill inherit the protocol contract with it.
  It mirrors the contract pinned by `test/knot/json_contract_test.clj`, so prose drift is caught at `bb test` time.
  The README's JSON paragraph and `SKILL.md`'s JSON section both link here.

- Error path for `--json` read commands now emits a structured error envelope on stdout with exit code 1 instead of a stderr message: `{"schema_version": 1, "ok": false, "error": {"code": "...", "message": "...", "candidates"?: [...]}}`.
  `knot show <missing> --json` carries `code: "not_found"`; partial-id ambiguity on `knot show --json` and `knot dep tree --json` carries `code: "ambiguous_id"` with a `candidates` array.
- `knot dep tree <unknown-id> --json` intentionally returns a *success* envelope with `data.missing: true` rather than a `not_found` error.
  Dep tree tolerates missing roots so consumers can discover broken `:deps` refs via the parent that links to them.
  JSON consumers should branch on `data.missing` distinctly from `ok: false`.
- Argument-parsing failures (e.g. `--limit 0`, missing required positional args) continue to die on stderr with exit 1.
  These are CLI-usage errors, not data conditions, and stay outside the JSON envelope contract.
- `--json` now extends to every mutating command: `create`, `start`, `status`, `close`, `reopen`, `dep`, `undep`, `link`, `unlink`, `add-note`.
  Agents skip the read-after-write round-trip, since the envelope's `data` is the touched ticket.
  Lifecycle commands and `add-note` emit the post-mutation ticket (single object, body included).
  `dep`/`undep` emit the `from` ticket with the updated `:deps`.
  `link`/`unlink` emit an array of every touched ticket (body excluded, ls-shape).
  `init` and `edit` are excluded: `init` is project setup with no ticket, and `edit` opens `$EDITOR`.
- `close --json` and `status <id> <terminal-status> --json` populate a top-level `:meta {:archived_to <path>}` slot, so callers do not infer archive routing.
  The envelope grows one slot, `{schema_version, ok, data, meta}`, and `:meta` is omitted when none applies.
- Error envelopes extend the read-side contract to writes: missing ids emit `{ok:false, error:{code:"not_found", message}}` on stdout with exit 1.
  Partial-id ambiguity emits `code: "ambiguous_id"` with a `candidates` array.
  `dep --json` cycle rejection emits `code: "cycle"` with the offending path under `error.cycle`.

- `knot update` gains `--add-ac <title>` and `--remove-ac <title>` for non-flip AC list management.
  Both are repeatable, idempotent, and match by exact title.
  They compose with `--ac <title> --done|--undone` in apply order add → flip → remove, so one `update` call can add a new AC, flip an existing one, and remove a third.
  `--body` now warns that the `## Acceptance Criteria` section is display-only on write; `--ac` points at the new deltas for non-flip ops.
  Bundled skill kept in sync.

### Changed

- Every command is now a strict-parsing command: unknown flags (`--tag` instead of `--tags`, `--bogus`, anything mistyped) exit non-zero with `knot: Unknown option: :<name>` on stderr instead of being silently absorbed by the parser.
  The `:restrict?` mechanism was already in place on `prime`/`ready`/`blocked`/`closed`/`info`/`migrate-ac`/`create`; this flips it on the remaining sixteen entries (`init`/`show`/`list`/`status`/`start`/`close`/`reopen`/`dep`/`dep tree`/ `undep`/`link`/`unlink`/`add-note`/`edit`/`update`/`check`), so the contract is uniform.
  A registry-invariant test pins the rule (`every?` `:restrict?` true on `help/registry`), and a new entry that omits it fails `bb test`.
  A small `edit-handler` cleanup ships with it, so the `:edit` entry flows through `(spec :edit)` like every other command instead of a hard-coded `{:spec {}}`.
  Pre-1.0 break window: anyone scripting `knot` with stale or misspelled flag names gets a loud failure instead of silent argv theft.
  Migration: run `knot <cmd> --help` for the canonical flag names.
  Bundled skill kept in sync.

- The intake status used by `knot create` and `knot reopen` now derives from the project's `:statuses` / `:active-status` / `:terminal-statuses` config (the first non-active, non-terminal status) instead of the hardcoded `"open"`.
  Default behavior is unchanged, since the default `:statuses` puts `"open"` first.
  Projects that customize statuses (e.g. `["todo" "active" "done"]`) get intake at `"todo"` automatically; no separate config key needed.

- The agent preamble emitted by `knot prime` for AFK mode is now selected via a new `:afk-mode` config key (default `"afk"`) instead of the hardcoded literal `"afk"`.
  Projects that rename the AFK mode (e.g. `:modes ["solo" "team"]` with `:afk-mode "solo"`) get the preamble on the renamed mode, and `:afk-mode nil` opts out.
  `knot info` surfaces the effective value under `allowed_values.afk_mode`; the `init` stub writes a documented `:afk-mode "afk"` line.

### Changed (BREAKING)

- All `--json` read commands now wrap their output in a tagged envelope `{schema_version: 1, ok: true, data: <payload>}` instead of returning a bare object or array.
  `knot list/ready/blocked/closed --json` change from `[ ... ]` to `{"schema_version": 1, "ok": true, "data": [ ... ]}`; `knot show --json`, `knot dep tree --json`, and `knot prime --json` change from `{ ... }` to `{"schema_version": 1, "ok": true, "data": { ... }}`.
  The `data` payload is unchanged from prior shapes.
- Acceptance criteria are now structured frontmatter (`acceptance: [{title, done}]`) instead of freeform `- [ ]` checkboxes in the body.
  The on-disk format changes accordingly: the `## Acceptance Criteria` section is **never stored** on disk, and `knot show` synthesizes it from frontmatter at display time alongside the inverse sections.
  A one-shot `knot migrate-ac` command (hidden from top-level help) lifts every body's existing checklist into structured frontmatter and strips the section; it is idempotent and safe to re-run.
  Existing acceptance bullets in body content will not appear in `knot show` until migrated.
- The v0.3 envelope contract is **extended**: `knot check --json` is the first command where `ok` mirrors a health verdict, so `ok: false` may coexist with a `data` slot when errors are present (`{schema_version: 1, ok: false, data: {issues: [...], scanned: {...}}}`).
  The earlier rule (`ok: false` ↔ `error` slot, no `data`) still holds for the cannot-scan case (exit 2).
  Argument-parse errors for `knot check` stay on stderr with exit 2 in both modes, matching the arg-parsing-stays-on-stderr policy.
- The active (in-progress) status now derives from the project's `:active-status` config key instead of the hardcoded string `"in_progress"`.
  Default behavior is unchanged, because `:active-status` defaults to `"in_progress"`.
  Projects that customize `:statuses` (e.g. `["open" "review" "shipped"]`) can define their own active status; `knot start`, `knot ready`'s blocked-ness check, and the ls-table render colors all read from the same source of truth.
  `knot check` validates `:active-status` via the `invalid_active_status` issue code.

### Removed

- `knot create` no longer accepts the `--afk` and `--hitl` shortcut flags.
  `--mode <value>` is the only path to set the mode at create time.
  The shortcuts baked the canonical mode names `"afk"` and `"hitl"` into CLI parsing, so projects that customize `:modes` (e.g. `["solo" "team"]`) would expose shortcuts for modes they do not have.
  `knot create` is also now a strict-parsing command: unknown flags (`--afk`, `--hitl`, `--body`, anything mistyped) exit non-zero with `knot: Unknown option: :<name>` on stderr, matching `prime`/`ready`/`blocked`/`closed`.
  The init stub documents the per-mode-shortcut invariant under `:modes` for future contributors.
  Pre-1.0 break window, no deprecation cycle.
  Migration: replace `--afk` with `--mode afk` and `--hitl` with `--mode hitl`.
- `knot dep cycle` is removed; `knot check --code dep_cycle` subsumes it.
  The semantic shift: `dep cycle` scanned only non-terminal tickets, while `knot check` scans the whole project (live + archive).
  Cycles among archived tickets now surface as issues, because they are real data-integrity problems if a ticket is later reopened.

### Fixed

- README, `.claude/skills/knot/SKILL.md`, and `.claude/skills/knot/references/json-protocol.md` document the `prime --json` stale-flag asymmetry: `stale: true` appears only on `in_progress` entries, never on the `ready` copy of the same ticket.
  Code behavior is unchanged; the narrow JSON surface is preserved on purpose.
- `knot info --bogus --json` (any unknown flag with `--json`) now emits the v0.3 `invalid_argument` error envelope on stdout instead of plain stderr text.
  Plain `knot info --bogus` still emits the existing `knot info: Unknown option: ...` stderr message.
  The exit code stays `1` on both paths, bringing `info` in line with the rest of the JSON-aware error contract.

- `--json` ticket payloads always include `tags`, `deps`, `links`, and `external_refs` as arrays: empty (`[]`) when the ticket has no value, populated otherwise.
  Previously these keys were absent for tickets that never set them, breaking `jq` pipelines like `knot list --json | jq -r '[.data[].tags[]]'` with `null[]` errors.
  Affects every command whose `--json` payload carries a ticket, on the read side (`list`, `show`) and the mutating side (`create`/`start`/`status`/`close`/`reopen`/`add-note`/`update`/`dep`/`undep`/`link`/`unlink`).
  On-disk YAML pruning is unchanged: `.md` files for tickets without values still omit the field, since the default is injected only at the JSON boundary.

- Cross-platform stability: `--json` envelopes emit POSIX-normalized paths on every OS.
  `meta.archived_to` (close and terminal-status) and `info --json`'s `paths.*` (cwd, project_root, config_path, tickets_dir, tickets_path, archive_path) all flow through `babashka.fs/unixify`, so JSON consumers don't branch on `os.name`.
  Stdout paths stay native, since humans paste them into the local shell.
  Stdout also uses `\n` line endings on every platform: Babashka's Windows JVM was emitting `\r\n` via `println`, surprising scripts that compared exact stdout (e.g. `knot --version`).
  The bundled skill (`SKILL.md`, `references/json-protocol.md`) and `docs/agents/testing.md` ("Cross-platform considerations") document the path policy and test rules.
  CI now treats `windows-latest` as a blocking gate; `continue-on-error` was dropped from the matrix.

- `knot create` no longer produces colliding ticket ids when two tickets are minted in the same millisecond.
  Id generation follows the standard ULID monotonic spec: same-ms (or backward-clock) calls reuse the prior timestamp and increment the random component by 1, and on the astronomically rare overflow at 1024 same-ms tickets the timestamp bumps by 1 and the random component resets.
  The atomic-create path uses `Files/write CREATE_NEW`, so even a millisecond collision that did slip through writes a fresh ticket via retry rather than overwriting an existing file.
  Eliminates the `prime-cmd-json-test` truncation flake, verified over 100 consecutive green `bb test` runs.

## [0.2.0] - 2026-04-30

### Added

- `knot prime` emits a new `## Recently Closed` section showing the last 3 closed tickets with their close `--summary`, extracted as the most recent `## Notes` block from the ticket body.
- `knot prime --mode afk` swaps the human-oriented preamble for an autonomous-agent flow checklist (claim → work → add-note → close).
- In-progress tickets older than 14 days carry a `[stale]` prefix in the text primer and a `"stale": true` field on JSON `in_progress` entries.
- `knot prime --json` output gains a top-level `recently_closed` array.
  JSON consumers should tolerate unknown keys; new fields may arrive in future minor versions.
- `knot.ticket/latest-note-content` extracts the most recent timestamped note from a ticket body, used by Recently Closed.
- A bundled `knot` skill at `.claude/skills/knot/SKILL.md` ships in the repo for agent platforms that load skill files.
  The README documents the recommended three-layer setup (project rules + SessionStart hook + skill).
- `.pi/extensions/knot-prime.ts` ships a Pi extension that runs `knot prime` at session start and injects the output as a hidden custom message, with a 10s timeout, a failure warning, and de-duplication of prior prime messages.
- `AGENTS.md` documents that issue tracking runs through the `knot` CLI and that `.tickets/` should not be read or modified directly.

### Changed

- The `ls` command is now an alias for the canonical `list`.
  `knot list` and `knot ls` are equivalent; help renders `list` with `ls` under the new ALIASES block.
- `knot prime` suppresses the `## In Progress` heading entirely on quiet projects: empty heading-only sections were dead weight on every session.
- `knot prime` Commands cheatsheet trimmed from 9 lines to 7 (`knot dep` and `knot dep tree` moved to the bundled skill).
- `knot prime` preamble's first line now references the `knot` skill, so non-Claude agents discover the canonical reference.
- `.claude/settings.json` SessionStart hook invokes `knot prime` directly; the bespoke `block-tickets-read.sh` hook was removed in favor of the AGENTS.md guidance.

### Removed

- `knot prime` no longer emits the `## Schema` cheatsheet.
  Agents reading the project schema should consult `.knot.edn` directly, or `knot prime --json` for the actionable subset.
  (Closes [kno-01kqdasr0384](.tickets/archive/kno-01kqdasr0384--knot-prime-schema-section-is-hardcoded-should.md).)
- `.claude/hooks/block-tickets-read.sh` removed; agent guidance to avoid hand-editing `.tickets/` now lives in `AGENTS.md`.

## [0.1.0] - 2026-04-29

### Changed (breaking)

- Ticket title now lives in frontmatter (`title:` as the second key, right after `id:`) instead of as the first `# H1` line of the body.
  `knot create` no longer prepends `# <title>` to the body, so with no `--description` / `--design` / `--acceptance` flags the body is empty.
  All read commands (`ls`, `ready`, `closed`, `show`, `dep tree`, `prime`) read the title directly from frontmatter and degrade to an empty title rather than crash when the field is missing.
  Existing ticket files in `.tickets/` were migrated in place.

### Changed

- `knot create` now prefers `:default-assignee` from `.knot.edn` over `git config user.name` when no `--assignee` is supplied.

### Added

- `ls --json`, `ready --json`, and `closed --json` now include `title` for each entry, a side effect of the frontmatter move.

## [0.0.1] - 2026-04-29

Initial release.
Knot is a file-based ticket store for AI-assisted development: plain Markdown tickets with YAML front-matter under `.tickets/`, queried and mutated through a babashka CLI.

### Added

- Project setup: `knot init` walks up from cwd to write `.knot.edn`, derives a prefix from the project directory name, and seeds the `.tickets/` tree.
- Lifecycle transitions: `knot create`, `show`, `status`, `start`, `close`, `reopen`, plus automatic moves between `.tickets/` and `.tickets/archive/` when a ticket reaches a terminal status.
- Discovery and listing: `knot ls`, `ready`, `blocked`, `closed`, with filters for `--status / --assignee / --tag / --type / --mode` and a `--limit` cap.
- Dependency graph: `knot dep` / `undep` for cycle-checked edges, `dep tree` for the recursive view, and `dep cycle` for repo-wide cycle detection.
- Symmetric links: `knot link` and `unlink` with computed inverse sections rendered in `show`.
- Annotation: `knot add-note` (positional / piped / `$EDITOR`) and `knot edit` for full-file editing, with a `--summary` field threaded through close.
- Session priming: `knot prime` emits an agent-directive primer, safe to wire into a SessionStart hook even outside a Knot project.
- Structured help system: `knot --help`, `knot help <cmd>`, and per-command `--help` / `-h`, with a single registry as the source of truth for both the parser spec and the rendered help.
- JSON output: every read command accepts `--json` for machine-readable output; TTY-aware color discipline keeps piped output clean.
- Distribution: `bbin` install metadata in `bb.edn` and a Clojure/babashka install path documented in the README.
- `knot --version` and a version banner on `knot --help`.
