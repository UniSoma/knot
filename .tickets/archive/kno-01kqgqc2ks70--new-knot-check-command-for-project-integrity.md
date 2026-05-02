---
id: kno-01kqgqc2ks70
title: New knot check command for project integrity validation
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-01T02:55:02.777819341Z'
updated: '2026-05-02T17:10:29.598160307Z'
closed: '2026-05-02T17:10:29.598160307Z'
tags:
- v0.3
- cli
- validation
- needs-triage
deps:
- kno-01kqgq9vhmvr
---

## Description

Add `knot check [<id>...]` — a single command that validates project integrity and surfaces issues.

With no ids, scans all tickets (live + archive) and config. With ids, scans just those tickets and any global checks. The command absorbs and replaces `knot dep cycle` (which is removed in this slice — its existence implied an invariant gap, but cycles can be introduced via hand-edits, so the check is real and belongs alongside other integrity checks).

Initial check codes:
- `dep_cycle` — error
- `unknown_id` — error (dangling `deps`/`links`/`parent` references)
- `invalid_status` — error (status not in `:statuses`)
- `invalid_type` — error
- `invalid_mode` — error
- `invalid_priority` — error (outside 0..4)
- `terminal_outside_archive` — error (terminal-status ticket sitting outside `archive/`, or non-terminal in `archive/`)
- `missing_required_field` — error (id, title, status)
- `frontmatter_parse_error` — error
- `invalid_active_status` — error (mirrors the kno-01kqdat9xssc constraint at scan time)

JSON output via the envelope from kno-01kqgq9vhmvr:

```json
{"schema_version": 1, "ok": false, "data": {"issues": [{"severity": "error", "code": "dep_cycle", "ids": [...], "message": "..."}, ...], "scanned": {"live": 10, "archive": 20}}}
```

CLI:

```
knot check [<id>...] [--json] [--severity error|warning] [--code <code>]
```

Exit codes:
- 0 — no errors (warnings allowed)
- 1 — one or more errors detected
- 2 — unable to scan (config invalid, tickets dir missing)

Stretch (optional in this slice; otherwise file follow-up): `stale_in_progress` warning that consolidates `prime`'s nudge logic. **Deferred** — file follow-up after this slice ships.

## Design

Design decisions resolved via grilling on 2026-05-02. Implementation slices in TDD order at the bottom.

### Module layout

- New file `src/knot/check.clj` (mirrored test `test/knot/check_test.clj`).
- Tolerant per-file loader (glob → slurp → try-parse) lives **inside `knot.check`** as a private helper. `store/load-all` stays strict so `show`/`ls`/`ready` continue to fail loudly on a corrupt file. Scope contains the surprise.
- CLI entry in `src/knot/cli.clj`, routing in `src/knot/main.clj`, help entry in `src/knot/help.clj`.
- `cli/dep-cycle-cmd` is **deleted**; `knot.check` calls `query/project-cycles` directly.
- `config/validate!` is refactored to extract `active-status-issue` as a non-throwing predicate. `validate!` continues to call it and throw (no behavior change for existing callers); `knot.check` calls the predicate directly so it can convert the result into an issue without going through the throwing path.
  - **Signature:** `(active-status-issue merged-config) → nil | {:code :invalid_active_status :message s}`. Returns `nil` when the invariant holds. `validate!` checks the return and throws `ex-info` with the same `:active-status :statuses :terminal-statuses` data shape it currently uses, preserving its public behavior.

### Scope tiers (id-list semantics)

Two-tier registry. Globals always run on the full ticket set; the per-ticket tier is filtered by the id list (or all when empty). The id list narrows the per-ticket tier; it does **not** suppress globals.

- `knot check` → per-ticket on every ticket + globals on full set.
- `knot check <id>...` → per-ticket on listed only + globals on full set.

| Tier | Codes |
|------|-------|
| Per-ticket | `invalid_status`, `invalid_type`, `invalid_mode`, `invalid_priority`, `missing_required_field`, `frontmatter_parse_error`, `terminal_outside_archive`, `unknown_id` |
| Global | `dep_cycle`, `invalid_active_status` |

Principle: per-ticket = single owning ticket; global = no single owner or config-level. `unknown_id` is per-ticket because it's owned by the ticket holding the broken ref (its `:ids` is `[holder-id]`, not the missing target). `dep_cycle` is global because no single ticket "owns" a multi-ticket cycle path.

### Issue record shape

```clj
{:severity :error                  ; or :warning ; keyword internally, string in JSON
 :code     :frontmatter_parse_error ; keyword internally, string in JSON
 :ids      []                       ; always vector; [] for path-only issues
 :message  "..."                    ; human-readable
 :path     "..."                    ; optional, file-level issues (parse error, terminal-outside-archive)
 :field    :status                  ; optional, for invalid_<field>; keyword internally, string in JSON
 :value    "wat"}                   ; optional, for invalid_<field>; raw value as found in frontmatter
```

**Encoding boundary:** issue records use Clojure keywords for `:severity`, `:code`, and `:field` internally. The JSON serializer at the envelope layer stringifies them (`:dep_cycle` → `"dep_cycle"`). `:value` is passed through as the raw frontmatter value (string, number, etc.). `:ids` and `:path` are already strings. Matches the existing `output/envelope-str` pattern — no special handling needed in `knot.check`; just construct issues with keywords and let the envelope layer encode.

- `:ids` is always a vector (possibly empty) — predictable for JSON consumers.
- `:path` is the locator for path-only issues. Symmetric with how `:field`/`:value` already work.
- For `dep_cycle`, `:ids` carries the canonical cycle path `[a b c a]` that `query/project-cycles` already returns.
- For `unknown_id`, `:ids` is `[holder-id]`; the missing target is named in `:message`. Covers `:deps`, `:links`, *and* `:parent` references.
- For `terminal_outside_archive`, the check is bidirectional: terminal-status tickets sitting outside `archive/` *and* non-terminal tickets sitting inside `archive/`.

### CLI surface

```
knot check [<id>...] [--json] [--severity error|warning] [--code <code>]
```

- Both `--severity` and `--code` are repeatable.
- **Combination:** OR within one flag, AND across flags. `--code dep_cycle --code unknown_id` matches either; `--severity error --code dep_cycle` matches both.
- **Unknown values:** reject `--severity` (closed enum) at parse time with stderr "unknown severity: …" and exit 2; accept unknown `--code` silently (open-ish — codes will grow; forward-compat for old scripts).
- **Filter timing:** filter applied **before** exit-code decision. Exit code reflects only what survives the filter (`grep` mental model — the user is asking "any of *these*?"). If you want the project-wide verdict, don't pass filters.
- `--severity warning` is wired now even though no warnings ship in this slice (stretch deferred). Forward-compat for the deferred `stale_in_progress` and any future warning code.

### Exit codes

- **0** — no errors in the filtered view (warnings allowed).
- **1** — one or more errors in the filtered view.
- **2** — cannot scan: no project root, malformed `.knot.edn` EDN, schema fields missing/wrong type, or no live + no archive + no `.knot.edn` (truly empty tree). Empty `<tickets-dir>` with valid config is a **clean exit 0** with `scanned: {live: 0, archive: 0}` — fresh-project case is normal.
- `invalid_active_status` is **not** exit 2 — it's surfaced as a global error issue via the extracted predicate (see Module layout above).

### JSON envelope

Uses the envelope from kno-01kqgq9vhmvr but **extends its precedent.** The closed dep ticket established `ok: false` ↔ `error` slot present, no `data`. `check` is the first command where `ok` mirrors the *health verdict*: `ok: false` will coexist with `data` when errors are present. The contract extension is documented in the envelope helper docstring so future commands know the rule.

| Outcome | Envelope shape | Exit |
|---------|----------------|------|
| Clean scan (no errors in filtered view) | `{schema_version: 1, ok: true, data: {issues: [], scanned: {…}}}` | 0 |
| Errors found | `{schema_version: 1, ok: false, data: {issues: [...], scanned: {…}}}` | 1 |
| Cannot scan | `{schema_version: 1, ok: false, error: {code, message}}` (no `data`) | 2 |

`scanned` counts files **attempted by glob**, not parsed-successfully. Parse-error files contribute to `scanned` *and* to the issues list — `scanned` is the denominator of attempted files, not a count of successes.

**Output channels** (matches existing precedent set by kno-01kqgq9vhmvr for `show`/`dep tree`):

| Mode | Outcome | Channel |
|------|---------|---------|
| `--json` | exits 0, 1, *or* 2 | **stdout** envelope (success or error) |
| no `--json` | exits 0 or 1 | **stdout** table + footer |
| no `--json` | exit 2 | **stderr** human message; nothing on stdout |

Argument parse errors (e.g. unknown `--severity`) stay on **stderr** with exit 2 in both modes — matches the "arg-parsing-stays-on-stderr" policy from the dep ticket's post-review addendum.

### Issue ordering

Total sort: severity desc → code asc → first-id asc → message asc. Same in JSON `data.issues` so consumers don't re-sort. Errors-first matches what a CI script wants to see at the head of output; sorting by code groups related issues together.

### Human-readable output

Table matching `ls`/`ready`/`blocked`/`closed` style (reuses `output/terminal-width` and existing table helpers).

Columns (in order): `SEVERITY`, `CODE`, `IDS` (comma-joined; `—` when empty for path-only issues), `MESSAGE`. `:path` and `:field`/`:value` are folded into the message text rather than getting their own columns — keeps the table narrow and uses the existing message-truncation behavior for long cycle paths.

Footer (every run, to stdout):

- Issues found: `5 issues (3 errors, 2 warnings) — scanned: live=10 archive=20`
- Clean: `knot check: ok — scanned: live=10 archive=20`

### Removing `knot dep cycle`

**Hard remove**, no redirect stub (we are pre-release). `knot dep cycle` falls into the existing "unknown dep subcommand" error path. README/docs reference `knot check`.

Deletion targets (file:line as of `main` at HEAD `e3d3871`):

- `src/knot/cli.clj:503` — `(defn dep-cycle-cmd ...)` — delete the function entirely.
- `src/knot/main.clj:287-294` — `(defn- dep-cycle-handler [_argv] ...)` — delete the function entirely.
- `src/knot/main.clj:302` — the `"cycle" (dep-cycle-handler (rest argv))` line inside `dep-handler` — delete this case from the `case` form.
- `src/knot/help.clj:248` — change `:subcommands [:dep/tree :dep/cycle]` → `:subcommands [:dep/tree]` in the `:dep` registry entry.
- `src/knot/help.clj:264-272` — the `:dep/cycle` registry entry (`{:group :graph :description "Scan open tickets for dep cycles." …}`) — delete the entire entry.
- `src/knot/help.clj:99` — docstring mentions `:dep/cycle` as an example registry key; update example to `:dep/tree` only (cosmetic but consistent).

Tests referencing `dep cycle` (find via `grep -rn "dep cycle\|dep-cycle" test/`): rewrite against `knot check --code dep_cycle` or delete if redundant with new `knot.check` test coverage.

Line numbers are pre-implementation references — re-verify before deleting in case other slices have shifted them.

### TDD slices (implementation order)

1. **Skeleton + `dep_cycle` (global).** `knot.check/run` returns `{:issues [] :scanned {…}}`; first check wraps `query/project-cycles`. Test: project with a cycle → one error issue with the cycle path in `:ids`.
2. **Per-ticket validators, one test → one validator at a time:** `invalid_status`, `invalid_type`, `invalid_mode`, `invalid_priority`, `missing_required_field`, `terminal_outside_archive` (both directions), `unknown_id` (covers `:deps`/`:links`/`:parent`), `frontmatter_parse_error`.
3. **`invalid_active_status` (global).** Refactor `config/validate!` to extract the predicate; wire `knot.check` to call it directly.
4. **Filter helpers.** OR-within-AND-across; reject unknown severity; accept unknown code; filter-then-exit.
5. **CLI integration.** `check-cmd` in `cli.clj`, routing in `main.clj`, exit codes 0/1/2, JSON envelope (success and error variants), help entry in `help.clj`.
6. **Remove `dep cycle`.** Delete `cli/dep-cycle-cmd`, `dep-cycle-handler`, the `"cycle"` case in `dep-handler`, and the `cycle` line in `knot help dep`. Update tests that referenced `dep cycle` (rewrite them against `knot check`).
7. **CHANGELOG.** New section noting added `knot check`, the BREAKING removal of `knot dep cycle`, and the envelope-contract extension (`ok: false` may now coexist with `data`).

## Acceptance Criteria

- [ ] `knot check` command implemented; routes through the envelope from kno-01kqgq9vhmvr (extending its contract: `ok: false` may coexist with `data`)
- [ ] All initial check codes implemented (`dep_cycle`, `unknown_id`, `invalid_status/type/mode/priority`, `terminal_outside_archive`, `missing_required_field`, `frontmatter_parse_error`, `invalid_active_status`)
- [ ] Per-ticket vs global tier split as documented in Design; `<id>...` argument narrows the per-ticket tier only
- [ ] Each issue carries `severity`, `code`, `ids` (always vector), `message`; optional `path`, `field`, `value` per shape spec
- [ ] `--severity` and `--code` filter flags work (repeatable, OR within / AND across); unknown severity rejected, unknown code accepted; filter applied before exit-code decision
- [ ] Exit codes: 0 clean / 1 errors / 2 unable-to-scan; `invalid_active_status` is an issue (not exit 2)
- [ ] Issues sorted: severity desc → code asc → first-id asc → message asc, in both JSON and text output
- [ ] `scanned` counts files attempted by glob (parse-error files included)
- [ ] Human output: table (`SEVERITY CODE IDS MESSAGE`) + footer summary line; clean run shows `knot check: ok — scanned: …`
- [ ] `knot dep cycle` hard-removed (handler + cli/dep-cycle-cmd deleted, `cycle` line removed from `knot help dep`); falls through to existing unknown-subcommand error
- [ ] `config/validate!` refactored to extract `active-status-issue` predicate without changing throwing behavior
- [ ] Tests cover at least one positive case per check code; integration tests cover all three exit codes and both JSON envelope shapes
- [ ] CHANGELOG covers the new command, the `dep cycle` removal (BREAKING), and the envelope-contract extension

## Notes

**2026-05-02T17:10:29.598160307Z**

Implemented new `knot check [<id>...]` command for project-integrity validation, on the v0.3 envelope. Slice 1: knot.check skeleton with run returning {:issues [] :scanned {…}}; first global check wraps query/project-cycles. Slice 2: per-ticket validators landed one-test-one-validator — invalid_status, invalid_type, invalid_mode (shared check-enum factory); invalid_priority (integer 0..4); missing_required_field (id/title/status, blank-as-missing, :path locator when id is the missing field); terminal_outside_archive (bidirectional — terminal-outside and non-terminal-inside both surface); unknown_id (deps/links/parent, owned by holder, value names the missing target); frontmatter_parse_error (loader-fed list, :ids=[], :path locator). Slice 3: extracted config/active-status-issue as a non-throwing predicate (:code :invalid_active_status :message s); validate! still throws via the same predicate (no public behavior change); knot.check wires it as a global error issue. Slice 4: filter-issues with OR-within / AND-across semantics, validate-filter-spec with closed severity enum + open code enum (returns {:error msg} on bad severity), :ids-filter that narrows the per-ticket tier (globals always run on the full set), and a total sort key (severity-desc → code-asc → first-id-asc → message-asc). Slice 5: knot.check/scan tolerant per-file loader (parse failures isolated, :scanned counts files attempted by glob including failures, :archived? boolean tagged at load time); cli/check-cmd returning {:exit :stdout :stderr} so main can route channels; output/envelope-str extended to a 2-arity {:ok? false} form so ok:false coexists with :data; main/check-handler with exit 0/1/2 (cannot-scan emits a JSON error envelope under --json, stderr message otherwise — matches arg-parsing-stays-on-stderr precedent); help registry entry under :project group; check-cmd unit tests + 4 end-to-end integration tests covering exit 0/1/2 and both JSON envelope shapes. Slice 6: hard-removed knot dep cycle — deleted cli/dep-cycle-cmd, main/dep-cycle-handler, the cycle case in dep-handler, the :dep/cycle help registry entry, dep-cycle-cmd-test, dep-cycle-end-to-end-test; updated registry-parity expected-cmd-keys; knot dep cycle now falls through to the unknown-dep-subcommand error path (knot dep: <from> and <to> ids are required, exit 1). Slice 7: CHANGELOG entries — Added knot check, BREAKING removal of knot dep cycle (with the dep_cycle scope shift from non-terminal-only to whole-project explicitly noted), envelope-contract extension. Verification: bb test → 233 tests, 1984 assertions, 0 failures (added 24 new check_test, 6 cli_test cases, 4 integration_test cases, 1 config_test case); clj-kondo baseline unchanged (4 errors / 5 warnings, all pre-existing tmp macro false positives + ticket.clj id-suffix-chars unused-private warning); live knot check on this repo prints knot check: ok — scanned: live=20 archive=26 and emits a clean ok:true envelope with --json. dep_cycle scope shift is intentional and called out in CHANGELOG: cycles among archived tickets are now real data-integrity issues that surface, since reopening them would invalidate the graph.
