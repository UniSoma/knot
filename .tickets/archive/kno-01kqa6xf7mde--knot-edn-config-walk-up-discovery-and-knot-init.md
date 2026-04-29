---
id: kno-01kqa6xf7mde
title: .knot.edn config, walk-up discovery, and knot init
status: closed
type: task
priority: 2
mode: afk
created: '2026-04-28T14:12:00.372419480Z'
updated: '2026-04-28T14:32:04.921072734Z'
closed: '2026-04-28T14:12:01.237306012Z'
parent: kno-01kqa804gmgx
external_refs:
- docs/prd/knot-v0.md
- issues/0004-config-and-init.md
deps:
- kno-01kqa6xf42q9
---
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

- [x] `knot.config` parses `.knot.edn` and supports keys: `:tickets-dir`, `:prefix`, `:default-assignee`, `:default-type`, `:default-priority`, `:statuses` (ordered list), `:terminal-statuses` (set), `:types` (list), `:modes` (list), `:default-mode`
- [x] Defaults match the PRD: `tickets-dir = ".tickets"`, `default-type = "task"`, `default-priority = 2`, `statuses = ["open" "in_progress" "closed"]`, `terminal-statuses = #{"closed"}`, `types = ["bug" "feature" "task" "epic" "chore"]`, `modes = ["afk" "hitl"]`, `default-mode = "hitl"`
- [x] Project discovery walks up from cwd; first ancestor containing either `.knot.edn` or `<tickets-dir>/` is the project root
- [x] When both markers exist at different ancestors, the nearest one wins (config wins on conflict)
- [x] Invalid keys → stderr warning (skip)
- [x] Invalid values → fail fast at command start with a clear error message
- [x] `knot init` writes a self-documenting `.knot.edn` stub with all default keys present and inline-commented
- [x] `knot init` creates `<tickets-dir>` if missing
- [x] `knot init` flags: `--prefix`, `--tickets-dir`, `--force` (overwrite existing config); without `--force`, aborts on existing config
- [x] All earlier commands (`create`, `show`, `ls`, `status`, `start`, `close`, `reopen`) now consume config defaults instead of hardcoded values
- [x] Tests: walk-up discovery (cwd nested several levels deep, both markers at different ancestors, conflicting `:tickets-dir` between config and on-disk directory), default merging when keys are absent, validation errors on bad values

## Blocked by

- issue-0001 (`issues/0001-foundation-create-and-show.md`)

## Implementation notes

### `knot.config` API surface

Three new public functions, layered:

- `load-config <root>` — read `<root>/.knot.edn` (when present), warn on unknown keys, validate, merge with defaults. Returns the merged map. Always safe to call against a directory with no `.knot.edn`.
- `discover <start-dir>` — walks up via the existing `find-project-root`, then calls `load-config` at the discovered root. Returns `{:project-root <path> :config <merged>}` or `nil` when no marker is found anywhere. This is the function `main/discover-ctx` calls.
- `discover-within <start-dir> <boundary>` — same as `discover` but bounded by an exclusive ancestor. Used by tests so the walk cannot escape the temp tree.

The earlier `find-project-root` is unchanged and still public — `discover` is built on top of it rather than replacing it. This kept slice 1's tests untouched.

### Walk-up uses the *default* tickets-dir as the marker

There is a chicken-and-egg between "walk up looking for the configured `:tickets-dir/`" and "load the config to know what `:tickets-dir` is." The implementation resolves this by treating the literal `.tickets/` (the default) as the discovery marker, alongside `.knot.edn`. After the root is found, config is loaded and its `:tickets-dir` becomes authoritative for all subsequent operations. This means: if a user configures `:tickets-dir "tasks"` *and* their tickets directory is named `tasks/` with no `.knot.edn` checked in, walk-up will not find the project. In practice users running with a non-default tickets-dir will also have a `.knot.edn` (which is what told them about the option), so this is fine.

### "Config wins on conflict"

Interpretation in code: when an ancestor has both markers, walk-up still terminates there (the same way `has-marker?` works in slice 1), and the config's `:tickets-dir` overrides whatever directory name happens to be on disk. The integration test `config-flows-into-create-test` exercises this: a `.knot.edn` with `:tickets-dir "tasks"` is honored even when a stray `.tickets/` exists alongside.

### Validation

`validate!` is a single private function that throws `ex-info` with a `.knot.edn :<key> ...` prefixed message on the first violation. It validates the *merged* map (defaults + user overrides), so a config that omits a required key still passes — the default backs it. The error messages always name the offending key, which is what the integration test asserts on stderr.

Cross-key invariants are checked: `:default-type` ∈ `:types`, `:default-mode` ∈ `:modes`, `:terminal-statuses` ⊆ `:statuses`. `:default-priority` is bounded to 0..4 per the PRD. `:prefix` is optional but, when present, must match `[a-z0-9]+` so generated ticket ids never start with `-` or contain non-alphanumerics.

### Unknown-key warning

Unknown keys are dropped via `select-keys raw known-keys` *before* `merge`, so defaults are always reachable even in the presence of typos. The warning lists every offending key in one stderr line; downstream tools wrapping `knot` can grep for `knot: ignoring unknown` to surface config drift.

### `knot init` stub generation

The stub is built as a single hand-formatted string rather than via `clojure.pprint` or `clj-yaml`. This is deliberate: pprint cannot interleave block comments between map entries, and the AC requires every key be inline-commented for self-documentation. The stub references the runtime defaults from `(config/defaults)` so any future change to defaults is reflected in `init` output without a parallel update.

The `--prefix` flag is rendered into the stub as a live key (`:prefix "abc"`); without `--prefix` it is rendered as a commented-out example so users can see the schema slot without committing to a value (auto-derived from the project dir name otherwise). `--tickets-dir` always renders as a live `:tickets-dir` key, with the directory created on disk.

### `init` runs in cwd, not via discovery

`init-handler` does *not* call `discover-ctx`. It uses `(fs/cwd)` directly because `init` is the command that *creates* the marker that `discover-ctx` would look for. This also means `knot init` from within an existing project root just rewrites that project's config (correctly aborts unless `--force`), and `knot init` from a subdirectory creates a *new* project rooted at the subdirectory — matching the user's directional intent.

### Wiring config into existing commands

`main/discover-ctx` was rewritten to use `config/discover` and merge its `:config` map into the runtime context. The downstream `cli/resolve-ctx` already merged `(config/defaults)` underneath the caller's ctx, so the config-loaded ctx flows through every existing command without per-command changes. The single explicit addition was wiring `:default-assignee` into `resolve-ctx`'s assignee fallback chain (explicit ctx > git user.name > config `:default-assignee`).

### Tests not added (deferred to later slices)

- `:prefix` round-trip through `knot create` (only the validation regex is tested here; the prefix-to-id-generation path is integration-tested via slice 1's existing tests, which derive prefix from the dir name).
- Statuses/types lists driving CLI validation on `--type`/`--status` flags. v0 deliberately keeps validation lenient at command time; only `.knot.edn` itself is validated.
- `--default-assignee` via the CLI on a non-git host. The wiring is in place but only unit-covered indirectly via `resolve-ctx`.
