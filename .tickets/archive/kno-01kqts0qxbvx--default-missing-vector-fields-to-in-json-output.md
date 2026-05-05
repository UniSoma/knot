---
id: kno-01kqts0qxbvx
title: Default missing vector fields to `[]` in `--json` output
status: closed
type: bug
priority: 2
mode: afk
created: '2026-05-05T00:36:12.843353738Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-05-05T00:47:52.467788061Z'
tags:
- v0.3
- json
- cli
links:
- kno-01kqgqegm782
- kno-01kqgqf4aw4j
- kno-01kqsm8xgjf3
acceptance:
- title: '`knot list --json` and `knot show --json` always include `tags`, `deps`, `links`, `external_refs` in every ticket''s JSON object — empty array when the ticket has no value, full array when it does.'
  done: false
- title: Same shape on `--json` envelopes from mutating commands routed through `touched-ticket-json` / `touched-tickets-json` (`start`, `close`, `update`, `dep`, `link`, etc.).
  done: false
- title: Optional scalars (`parent`, `assignee`, `closed`) remain absent when unset — no `null` emitted.
  done: false
- title: 'On-disk YAML frontmatter is unchanged: `cli.clj` continues to prune empty seqs at write time; tickets without tags do not gain a `tags: []` line in their `.md` file.'
  done: false
- title: '`knot list --json | jq -r ''[.data[].tags[]]''` succeeds on a project containing tickets with and without tags.'
  done: false
- title: The four normalized keys are declared in a single named constant inside `src/knot/output.clj`, discoverable for future vector fields.
  done: false
- title: 'Surfaces updated in the same commit:'
  done: false
- title: '`src/knot/output.clj` — `jsonify-ticket` injects the four vector defaults; named constant for the key list.'
  done: false
- title: '`test/knot/output_test.clj` — `jsonify-ticket` unit tests for default injection (absent → `[]`, present → unchanged) and key-set coverage.'
  done: false
- title: '`test/knot/integration_test.clj` — e2e: `knot list --json` and `knot show --json` for a tag-less ticket emit `tags: []`, same for the other three keys.'
  done: false
- title: '`CHANGELOG.md` — entry under `[Unreleased]`.'
  done: false
- title: 'Tests (TDD; `bb test`):'
  done: false
- title: '30-second grep audit before red-phase: search `cli_test` / `output_test` / `integration_test` for assertions referencing `:tags` / `:deps` / `:links` / `:external_refs` that depend on absence (e.g. `(not (contains? ...))`, `nil?` checks, `(count (keys ...))`); update those alongside the new assertions.'
  done: false
- title: 'Red-phase: write the new "always present" assertions; run.'
  done: false
- title: 'Green-phase: smallest change to `jsonify-ticket`.'
  done: false
- title: 'Lint baseline unchanged: `clj-kondo --lint src test` reports the existing 4 errors / 5 warnings only.'
  done: false
---

## Description

`knot list --json` and `knot show --json` (plus mutating-command envelopes via `touched-ticket-json` / `touched-tickets-json`) project tickets through `jsonify-ticket` at `src/knot/output.clj:172-184`, which passes the on-disk frontmatter through unchanged. Tickets without `tags` / `deps` / `links` / `external_refs` have no corresponding YAML key on disk — `src/knot/cli.clj:73-74` and `src/knot/cli.clj:326-330` deliberately prune empty seqs at write time to keep `.md` files clean — so those keys are absent from JSON.

That breaks intuitive jq pipelines, e.g.:

```
knot list --json | jq -r '[.data[].tags[]]'
```

fails on the first ticket without tags because `.tags` is `null` and `null[]` errors.

Other JSON renderers in the codebase already normalize this themselves: `dep-tree-json` uses `(or children [])`; `inverses->json-fields` always emits `blockers/blocking/children/linked` as arrays; `prime-json` uses `(or recently-closed [])`; `info-json`'s vectors are config-populated and always present. The inconsistency is local to `jsonify-ticket`.

JSON shape is informally stabilizing toward v0.3 — no published contract, no `schema_version` bump needed.

## Design

Normalize at the JSON boundary only. Disk YAML pruning is intentional (keeps `.md` files clean for humans) and stays untouched.

Inside `jsonify-ticket`, inject `[]` defaults for the four optional vector fields when absent. Defaults append at the end of the JSON object — preserves the clj-yaml ordered-map identity called out in the existing docstring, avoids re-triggering the 8-entry `(into {} ordered-map)` ordering bug, and JSON object key order is non-semantic.

Use a named constant for the vector-field set so contributors adding future optional vector fields (e.g. `acceptance_criteria` from kno-01kqgqdxbxye) have a single discoverable place to extend.

```clojure
(def ^:private json-vector-default-keys
  [:tags :deps :links :external_refs])

(defn- assoc-vector-defaults [fm]
  (reduce (fn [m k] (if (contains? m k) m (assoc m k [])))
          fm json-vector-default-keys))
```

Scope is **vector fields only**. Optional scalars (`parent`, `assignee`, `closed`) stay absent — the bug is iteration (a vector-only concept), and emitting `null` for unset scalars introduces "set vs. unset" ambiguity for zero user benefit (jq's `.parent` already returns `null` for both cases).

## Notes

**2026-05-05T00:47:52.467788061Z**

TDD vertical slice: red → green → refactor.

RED: New `jsonify-vector-defaults-test` in test/knot/output_test.clj covering all four JSON entry points — `show-json`, `ls-json`, `touched-ticket-json`, `touched-tickets-json` — asserting :tags/:deps/:links/:external_refs become `[]` when absent, present values pass through unchanged, and optional scalars (:parent/:assignee/:closed) stay absent. Initial run: 16 failures across the 4 paths × 4 keys.

GREEN: src/knot/output.clj — added `^:private json-vector-default-keys` constant ([:tags :deps :links :external_refs]) and a `reduce (fn [m k] (if (contains? m k) m (assoc m k [])))` inside `jsonify-ticket`. Defaults append at end of map (clj-yaml ordered-map identity preserved; JSON object key order is non-semantic). Docstring extended with the rationale.

Audit: pre-RED grep across cli_test/output_test/integration_test for absence assertions on the four keys in JSON envelopes turned up exactly four hits — all flipped to assert presence with `[]`:
- test/knot/cli_test.clj:1214 (undep-cmd-json drop-last-dep)
- test/knot/cli_test.clj:1948 (unlink-cmd-json drop-last-link)
- test/knot/integration_test.clj:1551 (undep --json end-to-end)
- test/knot/integration_test.clj:1689 (update --external-ref "" end-to-end)
On-disk :frontmatter absence assertions left untouched — disk pruning is preserved.

E2E: New `json-vector-defaults-end-to-end-test` in test/knot/integration_test.clj — 4 sub-tests: (1) `knot list --json` for a tagless ticket emits all four keys as `[]`; (2) `knot show --json` likewise; (3) on-disk .md for a tagless ticket contains no `tags:`/`deps:`/`links:`/`external_refs:` lines (pruning preserved); (4) `knot create --tags x,y --external-ref JIRA-1` followed by `show --json` round-trips populated values unchanged while the unset two stay `[]`.

Live smoke (mixed bare + tagged): `knot list --json | jq -r '[.data[].tags[]]'` returns `["x","y"]` cleanly — the exact pipeline named in the ticket.

CHANGELOG: New entry under `[Unreleased]` → `### Fixed` documenting the always-present-as-array contract for the four keys, the affected commands (read + mutating), and that disk YAML pruning is unchanged.

SKILL.md: Added a paragraph in the JSON-for-parsing section noting that ticket payloads always carry tags/deps/links/external_refs as arrays so `jq -r '.data[].tags[]'` is safe.

Suite: 277 tests / 2550 assertions / 0 failures (was 275 / 2507 / 0; +2 deftest, +43 assertions). Lint baseline unchanged (4 errors / 5 warnings, all pre-existing).

Files: src/knot/output.clj, test/knot/output_test.clj, test/knot/integration_test.clj, test/knot/cli_test.clj, CHANGELOG.md, .claude/skills/knot/SKILL.md.
