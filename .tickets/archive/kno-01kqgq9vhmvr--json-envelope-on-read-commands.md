---
id: kno-01kqgq9vhmvr
title: JSON envelope on read commands
status: closed
type: task
priority: 1
mode: afk
created: '2026-05-01T02:53:50.004055763Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-05-01T17:35:59.877129692Z'
tags:
- v0.3
- json
- cli
- needs-triage
links:
- kno-01kqe94cgmd2
acceptance:
- title: All `--json` read commands wrap output in the envelope
  done: false
- title: '`schema_version` set to `1` on every command'
  done: false
- title: 'Error path (unknown id, ambiguous id, validation failure) emits `{ok: false, error: {code, message, candidates?}}`'
  done: false
- title: 'Partial-id ambiguity error has `code: "ambiguous_id"` and populates `candidates`'
  done: false
- title: Snapshot tests cover envelope shape per command (success + at least one error path)
  done: false
- title: Existing tests updated for the new shape
  done: false
- title: CHANGELOG entry documents the breaking change
  done: false
---

## Description

Adopt the tagged JSON envelope `{schema_version, ok, data?, error?}` as the single output shape for every `--json` read command: `list`, `show`, `ready`, `blocked`, `closed`, `prime`. This is a v0.3 break — `list/ready/blocked/closed --json` change from a bare ticket array to `{schema_version: 1, ok: true, data: [...]}`; `show --json` and `prime --json` change from a bare object to `{schema_version: 1, ok: true, data: {...}}`.

On error (unknown id, ambiguous partial id, validation failure), `ok: false` carries `{code, message, candidates?}`. Partial-id ambiguity already exits 1 with candidates listed inline; under the envelope it surfaces as `{ok: false, error: {code: "ambiguous_id", candidates: [...]}}` in JSON mode.

The `warnings: []` slot is reserved for future use; do not populate in this slice. Foundational for every other v0.3 slice that touches JSON.

## Notes

**2026-05-01T17:35:59.877129692Z**

Adopted tagged JSON envelope {schema_version: 1, ok: true|false, data?, error?} as the single shape for every --json read command (list/ls/ready/blocked/closed/show/dep tree/prime). Added two public helpers in knot.output: envelope-str (success) and error-envelope-str (failure), both serializing via array-map for stable key order. Refactored show-json/ls-json/dep-tree-json/prime-json to wrap their existing payload through envelope-str — payload shapes inside :data are unchanged from v0.2. Wired error envelope through main.clj's show-handler and dep-tree-handler: not_found (no ticket matching id) and ambiguous_id (partial-id collisions, with :candidates [...]) now emit JSON to stdout with exit 1 instead of a stderr message; non-json paths preserve the prior die-on-stderr behavior. TDD: added envelope-success-test, envelope-error-test, show-json-envelope-test, ls-json-envelope-test, dep-tree-json-envelope-test, prime-json-envelope-test in output_test, and read-cmd-error-envelope-test (3 cases: missing id, ambiguous show, ambiguous dep tree) in integration_test — all watched fail before implementing. Updated existing JSON-shape assertions across output_test/cli_test/integration_test to expect the envelope (data shape inside :data is identical, so str/includes? assertions on inner keys carried through unchanged). dep-tree-json snapshot test pinned to the new envelope-wrapped exact bytes. CHANGELOG: added [Unreleased] section calling out the BREAKING shape change plus the new error envelope contract. Final suite: 205 tests, 1769 assertions, 0 failures, 0 errors. clj-kondo unchanged from baseline (3 pre-existing errors, 5 pre-existing warnings; zero new). End-to-end smoke verified: `ls --json` returns {schema_version:1, ok:true, data:[...]}, `show no-such-id --json` returns {schema_version:1, ok:false, error:{code:not_found, message:...}} with exit 1.

**2026-05-01T18:03:32.996037322Z**

Code review pass (post-close addendum). Reviewer flagged 3 Important + 2 Minor items, all addressed before commit: (1) dep-tree-on-missing-root behavior pinned with a regression test and documented in dep-tree-json docstring + CHANGELOG — intentional asymmetry (ok:true, data.missing:true) vs show (ok:false, not_found), so consumers can discover broken :deps refs via parent traversal; (2) integration test read-cmd-error-envelope-test rewritten to parse JSON via cheshire, asserting structured fields (schema_version, ok, error.code, error.candidates, absence of :data on error) instead of substring matching — the executable contract for downstream consumers is now actually tight; (3) new envelope-error-test sub-case pins extra-keys-passthrough on error-envelope-str so future closed-set refactors can't silently break the docstring promise; (4) dropped redundant (vec ...) on :candidates in main.clj — knot.store/ambiguous! already builds it via mapv; (5) CHANGELOG: long line wrapped to ~75 cols, added explicit notes about the dep-tree asymmetry and arg-parsing-stays-on-stderr policy. Final suite: 205 tests, 1775 assertions, 0 failures. Lint baseline unchanged.
