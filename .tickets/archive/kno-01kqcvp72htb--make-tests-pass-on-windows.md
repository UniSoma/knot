---
id: kno-01kqcvp72htb
title: Make tests pass on Windows
status: closed
type: task
priority: 3
mode: hitl
created: '2026-04-29T14:53:31.601460483Z'
updated: '2026-05-06T20:25:02.676872548Z'
closed: '2026-05-06T20:25:02.676872548Z'
links:
- kno-01kqgqafcxvv
- kno-01kqgqfwk4h1
acceptance:
- title: All 13 currently-failing Windows tests pass.
  done: false
- title: '`bb test` exits 0 on `windows-latest` in CI.'
  done: false
- title: '`continue-on-error: ${{ matrix.os == ''windows-latest'' }}` is removed from `.github/workflows/ci.yml`.'
  done: false
- title: No regressions on `ubuntu-latest` or `macos-latest`.
  done: false
- title: '`docs/agents/testing.md` has a "Cross-platform considerations" section covering `fs/components`, `fs/unixify`, the path-shape anti-pattern, and the Windows blocking-gate.'
  done: false
tags:
- v0.3
---

## Description

After enabling CI on a 3-OS matrix, the `windows-latest` job surfaced 13 test failures (originally 14; the UTF-8 `→` arrow failure was resolved in `8ef8c6e`). This ticket tracks fixing them so we can drop `continue-on-error` from the workflow and treat Windows as a blocking platform.

The fix combines targeted test rewrites with two small source changes that *strengthen* — rather than silence — Windows behavior. Verification is CI-only (no local Windows access).

## Failure inventory (re-baselined 2026-05-06)

**12 path-separator failures** — assertions baked POSIX `/` separators into substring/prefix/regex checks of paths that come back native (`\`) on Windows:

- 6× stdout `(str/includes? path "/archive/")` — `cli_test.clj` (status-cmd, close-cmd, summary-on-close), `integration_test.clj` (close-with-summary)
- 4× JSON envelope `meta.archived_to` — `cli_test.clj` (status-cmd-json, close-cmd-json), `integration_test.clj` (mutating-json), `json_contract_test.clj` (×2: close + status terminal-transition)
- 2× built expectations `(str/starts-with? out (str ... "/.tickets/"))` / `"/tasks/"` — `integration_test.clj` (create-from-subdirectory, config-flows-into-create)
- 1× regex `#".+/\.tickets/kno-[0-9a-z]{12}\.md"` — `cli_test.clj:130` (empty-title-bare-id)

**1 line-ending failure** — `help_test.clj:717` (`version-flag-routing-test`): expected `"0.2.0\n"`, got `"0.2.0\r\n"`. On Babashka's Windows JVM, `println` emits `\r\n` because SCI delegates to `PrintWriter.println()` which uses `System.lineSeparator()`.

## Design

### Path policy (the rule)

Paths inside `--json` envelopes are POSIX-normalized; paths printed to stdout stay native. Rationale: JSON is consumed programmatically and benefits from a stable cross-OS wire shape; stdout paths are read by humans on the local machine and should match the local shell so copy-paste works. Going forward, every path emitted in a `--json` field flows through `babashka.fs/unixify`.

### Source changes

**1. POSIX-normalize paths in `--json` envelopes via `fs/unixify`**

Use `babashka.fs/unixify` directly — no project-specific helper. Two call-site groups:

- `src/knot/cli.clj:330` — `{:meta {:archived_to (fs/unixify saved)}}`
- `src/knot/cli.clj:1268-1281` (`info-data` `:paths` map) — wrap all six path values: `:cwd`, `:project_root`, `:config_path`, `:tickets_dir`, `:tickets_path`, `:archive_path`.

**2. Force `\n` line endings on stdout**

Two surgical edits:

- `src/knot/main.clj:172` — change `println-out`'s else-branch from `(println s)` to `(print (str s "\n"))`. Routes every command's stdout through `\n` regardless of platform.
- `src/knot/main.clj:920` — replace `(println version/version)` with `(println-out version/version)` so `--version` short-circuits through the same chokepoint. Without this, the `println-out` fix doesn't catch `version-flag-routing-test` because `--version` bypasses every command handler.

Stdout-only. Stderr `println` sites are deliberately untouched — stderr is for diagnostics, not pipelines.

### Test changes — structural rewrites

Do **not** introduce a `posix-path` test helper that normalizes-then-string-compares; that would silence rather than fix.

- **6× stdout substring checks** → `(some #{"archive"} (map str (fs/components path)))`. Pins the structural claim ("path has an `archive` component") rather than a string-shape claim. Add one inline comment on a representative site explaining the `(map str ...)` — `fs/components` returns `Path` segments, not strings.
- **4× JSON envelope checks** → keep assertions as-is; source change #1 makes them pass on Windows. They now serve as wire-contract assertions.
- **2× built expectations** → use `(str (fs/path (fs/canonicalize tmp) ".tickets") java.io.File/separator)` to preserve the trailing-separator semantics of the original `/.tickets/` prefix check. Without the explicit separator, the assertion would silently weaken — `<canonical>/.tickets` would also match `<canonical>/.tickets-backup/...`.
- **1× regex** (`cli_test.clj:130`) → split into a `fs/components` membership check (parent dir is `.tickets`) plus a filename-only regex (`#"kno-[0-9a-z]{12}\.md"`).
- **1× line-ending test** → passes unchanged once source change #2 lands.

### Doc note (`docs/agents/testing.md`)

Add a new top-level section "Cross-platform considerations" between "Running Tests" and "Test file conventions". Content:

- Anti-pattern callout: don't bake `/` into path-shape assertions (e.g. `(str/includes? path "/archive/")`) — Windows-failure trap.
- `fs/components` for asserting structural path claims in tests: `(some #{"archive"} (map str (fs/components path)))`.
- `fs/unixify` rule: any path emitted inside a `--json` envelope flows through `fs/unixify`. Stdout paths stay native so humans can copy-paste them into the shell.
- `windows-latest` is a blocking CI gate.

(`str/trim-newline` is intentionally omitted — the line-ending issue is fixed at the source, not the test side.)

### Audit

5-minute final grep pass after the targeted fixes (`grep -rn '"/' src/`, `grep -rn 'println\b' src/`) as a confidence check. Not a budgeted audit step — Q1 + Q2 already absorbed every concrete instance the audit would have found.

### Execution shape

Single draft PR, four commits:

1. Source changes: `fs/unixify` at `archived_to` + six `info-data` paths; `(println-out version/version)` swap at `main.clj:920`; `println-out` else-branch fix at `main.clj:172`.
2. Test fixes (13 sites, structural).
3. `docs/agents/testing.md` "Cross-platform considerations" section.
4. Drop `continue-on-error: ${{ matrix.os == 'windows-latest' }}` from `.github/workflows/ci.yml`.

Push as draft, iterate via CI until Windows is green, then mark ready for review.

### Contingency

Strict atomicity with a 3-iteration time-box. If after 3 CI cycles a stuck test remains, fall back: skip the failing test on Windows with a `(when-not (windows?) ...)` guard naming a follow-up ticket id, remove `continue-on-error` for the rest of the suite, and ship the partial fix. Preserves the gate for everything else; a future regression on any fixed test trips immediately.

## Notes

**2026-05-06T20:25:02.676872548Z**

Shipped Windows green across the 3-OS CI matrix; continue-on-error dropped, windows-latest is now a blocking gate.

Source changes (cli.clj, main.clj):
  - meta.archived_to (close/terminal-status JSON envelope) and info-data's six :paths values (cwd, project_root, config_path, tickets_dir, tickets_path, archive_path) now flow through babashka.fs/unixify — --json wire shape is POSIX-normalized on every OS so consumers don't branch on os.name.
  - println-out's else-branch swapped to (print (str s "\n")) and the --version short-circuit routed through println-out — Babashka's Windows JVM was emitting \r\n via println, surprising scripts that compared exact stdout.

Path policy: --json envelope paths are POSIX-normalized; stdout paths stay native (humans copy-paste them into the local shell). Documented in docs/agents/testing.md "Cross-platform considerations" alongside the fs/components anti-pattern callout, the fs/unixify rule, and the windows-latest blocking-gate.

Test changes — structural rewrites, no silencing:
  - 6× stdout substring `(str/includes? path "/archive/")` → `(some #{"archive"} (map str (fs/components path)))` in cli_test/integration_test (status-cmd, close-cmd, summary-on-close, reopen, link-precondition, close-with-summary).
  - 4× JSON envelope `meta.archived_to` checks unchanged — source change covers them; they now serve as wire-contract assertions.
  - 2× built expectations in integration_test → `(str (fs/path (fs/canonicalize tmp) "<dir>") java.io.File/separator)` to preserve trailing-separator semantics so siblings like `.tickets-backup/` still get rejected.
  - 1× cli_test.clj:130 regex split into fs/components membership + filename-only regex.
  - 1× help_test.clj:717 line-ending — passes unchanged after source change.

Iteration 1 of the design's 3-cycle CI time-box turned up two clusters the inventory had missed: info-cmd-paths-block-test (7 sites — regression from the fs/unixify source change; wrapped expected side in fs/unixify) and store_test.clj (ticket-path-test 3 sites + save-archive-move + save-self-heals 4 sites). Fall-back contingency unused — Windows went green on cycle 1.

Bundled SKILL.md + references/json-protocol.md + CHANGELOG synced. Tests 326/4187/0 on Linux; lint baseline preserved (4 errors / 5 warnings, all pre-existing). 6 commits: 24c00ca, 3ec8120, db428eb, aeeedd7, ba689d8, 1ba0625.
