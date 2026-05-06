---
id: kno-01kqcvp72htb
title: Make tests pass on Windows
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-29T14:53:31.601460483Z'
updated: '2026-05-06T00:45:25.475978602Z'
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
- title: '`docs/agents/testing.md` has a paragraph on cross-platform test patterns (`fs/components`, `str/trim-newline`).'
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

**1 line-ending failure** — `help_test.clj:658` (`version-flag-routing-test`): expected `"0.2.0\n"`, got `"0.2.0\r\n"`. Windows JVM `println` emits `\r\n`.

## Design

### Source changes

**1. POSIX-normalize paths in JSON envelopes** (`src/knot/output.clj` + `src/knot/cli.clj`)

Add a helper:

```clojure
;; src/knot/output.clj
(defn path->json-str
  "Stringify a path for emission inside a --json envelope. POSIX-normalizes
   the separator so JSON consumers see a stable wire shape regardless of OS."
  [p]
  (str/replace (str p) java.io.File/separator "/"))
```

Call it from `cli.clj:254`:

```clojure
{:meta {:archived_to (output/path->json-str saved)}}
```

Effect: `--json` output emits forward-slash paths everywhere; the v0.3 JSON contract becomes platform-stable. Stdout paths stay native so Windows users can copy-paste them into a shell. Future path-bearing JSON fields use the same helper.

**2. Force `\n` line endings on stdout** (`src/knot/main.clj:148-156`)

Change `println-out`'s else-branch from `(println s)` to `(print (str s "\n"))`. Single chokepoint, applies to every knot command. Aligns with `git`/`node`/`cargo` convention so shell pipelines like `version=$(knot --version)` work uniformly on Git Bash/WSL.

### Test changes — Strategy B (structural rewrites)

Do **not** introduce a `posix-path` test helper that normalizes-then-string-compares; that would silence rather than fix.

- **6× stdout substring checks** → `(some #{"archive"} (map str (fs/components path)))`. Pins the structural claim ("path has an `archive` component") rather than a string-shape claim.
- **4× JSON envelope checks** → keep assertions as-is; source change #1 makes them pass on Windows. They now serve as wire-contract assertions.
- **2× built expectations** → use `(str (fs/path (fs/canonicalize tmp) ".tickets"))` etc. Same idiom already in use at `store_test.clj:535`.
- **1× regex** (`cli_test.clj:130`) → split into a `fs/components` membership check (parent dir is `.tickets`) plus a filename-only regex (`#"kno-[0-9a-z]{12}\.md"`).
- **1× line-ending test** → passes unchanged once source change #2 lands.

### Light audit (~30 min)

After the targeted fixes, sweep `src/` for hardcoded `"/"` in path construction and `"\n"` in I/O code. Fix anything obvious; file follow-up tickets for anything ambiguous. Aim: catch the next tier of latent Windows issues without a full-audit rabbit hole.

### Regression prevention

Add a paragraph to `docs/agents/testing.md` naming the `fs/components` and `str/trim-newline` patterns and noting that `windows-latest` is a blocking gate. The CI gate (after `continue-on-error` removal) is the real protection; the doc helps agents and humans avoid the foot-gun proactively.

### Execution shape

Single draft PR, four commits:

1. Source changes (`output/path->json-str` + `println-out` `\n` fix)
2. Test fixes (13 sites, structural)
3. `docs/agents/testing.md` cross-platform paragraph
4. Drop `continue-on-error: ${{ matrix.os == 'windows-latest' }}` from `.github/workflows/ci.yml`

Push as draft, iterate via CI until Windows is green, then mark ready for review.

## Acceptance Criteria

- [ ] All 13 currently-failing Windows tests pass.
- [ ] `bb test` exits 0 on `windows-latest` in CI.
- [ ] `continue-on-error: ${{ matrix.os == 'windows-latest' }}` is removed from `.github/workflows/ci.yml`.
- [ ] No regressions on `ubuntu-latest` or `macos-latest`.
- [ ] `docs/agents/testing.md` has a paragraph on cross-platform test patterns (`fs/components`, `str/trim-newline`).
