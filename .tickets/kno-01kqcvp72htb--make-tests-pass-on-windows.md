---
id: kno-01kqcvp72htb
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-29T14:53:31.601460483Z'
updated: '2026-04-29T14:53:31.601460483Z'
---

# Make tests pass on Windows

## Description

After enabling CI on a 3-OS matrix, the windows-latest job surfaced 14 test failures. This ticket tracks fixing them so we can drop continue-on-error from the workflow and treat Windows as a blocking platform.

## Categories of failure

### 1. Hardcoded `/` separators in test assertions (~11 failures)

Tests written against POSIX-style paths fail on Windows because paths use `\`. Examples:

- `(str/includes? path "/archive/")` — fails on `C:\...\archive\...`
- `(str/starts-with? out (str tmp "/" tickets-subdir "/"))` — separator mismatch
- regexes with `/` baked in

Affected files (from CI output):

- `test/knot/cli_test.clj` — `create-cmd-test`, `status-cmd-test`, `close-cmd-test`, `summary-on-close-test`
- `test/knot/integration_test.clj` — `create-from-subdirectory-test`, `config-flows-into-create-test`, `close-with-summary-end-to-end-test`
- `test/knot/store_test.clj` — `ticket-path-test` (3 assertions), `save-self-heals-location-test`, `save-archive-move-test`

Note: `store/ticket-path` returns native-separator paths on Windows (e.g. `\p\tickets-dir\...`). This is probably the right knot behavior — Windows users expect Windows-style paths in stdout — so the **tests** are wrong here, not the source.

**Fix shape options:**

```clojure
;; Option A: normalize separators before string compare
(is (str/includes? (str/replace path "\\" "/") "/archive/"))

;; Option B: structural check via path components
(is (some #{"archive"} (map str (fs/components path))))
```

Option B is cleaner and platform-agnostic by construction; Option A is a smaller diff. Pick one and apply consistently across all sites.

### 2. `\r\n` vs `\n` line endings (1 failure)

`test/knot/help_test.clj:541` (`version-flag-routing-test`):

```
expected: "0.0.1\n"
actual:   "0.0.1\r\n"
```

Windows `println` emits `\r\n`. Fix: `(str/trim-newline out)` or compare with `(str/trim ...)` on both sides.

### 3. UTF-8 encoding on Windows console (1 failure)

`test/knot/integration_test.clj:583` (`dep-cycle-end-to-end-test`) — expected the `→` arrow character; got `?`. The Windows JVM defaults to cp1252 file encoding, which can't encode `→`, so the character is replaced with `?` on output.

This is **environmental**, not a code fix. Options:

- Workflow env: `JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8'` (covers JVM-level)
- Pre-step: `chcp 65001` to set console codepage (Windows shell only)
- bb-specific: `BABASHKA_OPTS: '-Dfile.encoding=UTF-8'` or similar

Try `JAVA_TOOL_OPTIONS` first — simplest, least invasive.

## Acceptance Criteria

- [ ] All 14 listed Windows failures are resolved.
- [ ] `bb test` exits 0 on `windows-latest` in CI.
- [ ] `continue-on-error: ${{ matrix.os == 'windows-latest' }}` is removed from `.github/workflows/ci.yml`.
- [ ] No regressions on `ubuntu-latest` or `macos-latest`.
