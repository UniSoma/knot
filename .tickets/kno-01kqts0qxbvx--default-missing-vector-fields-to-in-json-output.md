---
id: kno-01kqts0qxbvx
title: Default missing vector fields to `[]` in `--json` output
status: open
type: bug
priority: 2
mode: afk
created: '2026-05-05T00:36:12.843353738Z'
updated: '2026-05-05T00:36:18.008306746Z'
tags:
- v0.3
- json
- cli
links:
- kno-01kqgqegm782
- kno-01kqgqf4aw4j
- kno-01kqsm8xgjf3
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

## Acceptance Criteria

- `knot list --json` and `knot show --json` always include `tags`, `deps`, `links`, `external_refs` in every ticket's JSON object — empty array when the ticket has no value, full array when it does.
- Same shape on `--json` envelopes from mutating commands routed through `touched-ticket-json` / `touched-tickets-json` (`start`, `close`, `update`, `dep`, `link`, etc.).
- Optional scalars (`parent`, `assignee`, `closed`) remain absent when unset — no `null` emitted.
- On-disk YAML frontmatter is unchanged: `cli.clj` continues to prune empty seqs at write time; tickets without tags do not gain a `tags: []` line in their `.md` file.
- `knot list --json | jq -r '[.data[].tags[]]'` succeeds on a project containing tickets with and without tags.
- The four normalized keys are declared in a single named constant inside `src/knot/output.clj`, discoverable for future vector fields.
- Surfaces updated in the same commit:
  - `src/knot/output.clj` — `jsonify-ticket` injects the four vector defaults; named constant for the key list.
  - `test/knot/output_test.clj` — `jsonify-ticket` unit tests for default injection (absent → `[]`, present → unchanged) and key-set coverage.
  - `test/knot/integration_test.clj` — e2e: `knot list --json` and `knot show --json` for a tag-less ticket emit `tags: []`, same for the other three keys.
  - `CHANGELOG.md` — entry under `[Unreleased]`.
- Tests (TDD; `bb test`):
  - 30-second grep audit before red-phase: search `cli_test` / `output_test` / `integration_test` for assertions referencing `:tags` / `:deps` / `:links` / `:external_refs` that depend on absence (e.g. `(not (contains? ...))`, `nil?` checks, `(count (keys ...))`); update those alongside the new assertions.
  - Red-phase: write the new "always present" assertions; run.
  - Green-phase: smallest change to `jsonify-ticket`.
- Lint baseline unchanged: `clj-kondo --lint src test` reports the existing 4 errors / 5 warnings only.
