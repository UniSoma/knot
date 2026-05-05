---
id: kno-01kqwgeba7jz
title: JSON contract test suite — pin every --json command's shape against runtime
status: closed
type: task
priority: 2
mode: afk
created: '2026-05-05T16:44:50.374838263Z'
updated: '2026-05-05T17:15:26.654212401Z'
closed: '2026-05-05T17:15:26.654212401Z'
tags:
- v0.3
- json
- testing
- cli
acceptance:
- title: 'Envelope invariants pinned in one place: schema_version=1, ok always present, data XOR error (with the documented knot check exception where ok:false may coexist with data).'
  done: true
- title: Per-command data shape asserted for each --json command (key presence + types) — list, show, ready, blocked, closed, prime, info, check, dep tree, plus the mutators create, start, status, close, reopen, dep, undep, link, unlink, add-note, update, migrate-ac.
  done: true
- title: 'Vector-default contract pinned: tags/deps/links/external_refs always arrays in ticket payloads (read + mutating envelopes via touched-ticket-json / touched-tickets-json).'
  done: true
- title: meta.archived_to pinned for close --json and any status transition to a terminal status.
  done: true
- title: 'Error envelopes pinned: not_found (any id-resolving command), ambiguous_id with candidates array, cycle with error.cycle path on dep --json, check exit-2 cannot-scan envelope.'
  done: true
- title: Tests run on bb test; lint baseline unchanged.
  done: true
- title: CHANGELOG entry under [Unreleased].
  done: true
links:
- kno-01kqgqegm782
- kno-01kqts0qxbvx
---

## Description

Add test/knot/json_contract_test.clj (or similar) that pins the runtime JSON envelope shape for every --json command. Replaces the snapshot-test conformance AC from kno-01kqgqegm782 (closed won't-do) — the drift-detection benefit, separated from the abandoned `knot schema` CLI surface.

Coverage target: every --json command, happy path + at least one error path each. Envelope invariants asserted once; per-command `data` shape asserted per command.

Open question for the ticket-grabber: is this a v0.3 release blocker (add as blocker on kno-01kqgqfwk4h1) or a v0.4 follow-up? Default position: blocker — drift in the JSON protocol is exactly the regression class v0.3 sets out to avoid.

## Notes

**2026-05-05T17:15:17.064171890Z**

TDD vertical slice: 16 RED→GREEN slices, all GREEN on first run for envelope/data shape; one RED found a *real* contract surprise that needed splitting (see slice 8 below).

Deliverable: test/knot/json_contract_test.clj (1290 lines, 18 deftests, 1325 assertions).

Layout — by AC bullet, with self-contained fixtures per slice:
- envelope-invariants-read-commands-test (9 commands × 5 invariants)
- envelope-invariants-mutating-commands-test (12 commands)
- check-exception-ok-false-with-data-test (the documented carve-out — ok:false coexists with data on integrity errors)
- vector-default-contract-{read,mutating}-commands-test (tags/deps/links/external_refs always arrays)
- meta-archived-to-contract-test (close + status→terminal positive; start/status→non-terminal/update negative pinned too)
- error-envelope-{not-found,ambiguous-id,cycle,check-cannot-scan}-contract-test
- data-shape-{list-commands,show,prime,info,check,dep-tree,single-ticket-mutators,multi-ticket-and-migrate}-test

Helpers (pinned in one place so a new --json command needs a one-line addition):
- assert-envelope-invariants! (schema_version + ok-presence + data XOR error, with check-exception flag)
- assert-ticket-vector-defaults! (the four vector defaults always arrays)
- assert-ticket-payload-shape! (canonical 8 required keys + types + body? body-included branching)
- assert-not-found-envelope! / assert-ambiguous-envelope! (full error-envelope shape)
- seed-read-fixture! / seed-ambiguous-fixture! (shared across slices that need >1 read command or >1 ambiguous probe)

Slice-8 surprise (drove the only test rewrite): `dep --json <real> <missing>` exits 0 with ok:true, NOT a not_found envelope. Same for `undep <real> <missing>` and `unlink <real> <missing>`. The from id is strict-resolved; the to id is intentionally soft-resolved per cli.clj:294-297 (broken refs land verbatim — surfaced as `[missing]` markers at render time, owned by `knot check`'s `unknown_id` validator). `link` is the exception — both ids resolve strictly because both sides are written. The asymmetry is now pinned positively: not_found on `from` for dep/undep/unlink, on either side for link, and the `to`-side soft-resolution is asserted as a *positive* contract (ok:true; missing id stored verbatim) so a future tightening to strict resolution would have to deliberately update the test.

Also pinned the `dep tree` asymmetry vs `show`: an unknown root resolves to `{ok:true, data:{id, missing:true}}`, not `not_found` — already covered in integration_test/read-cmd-error-envelope-test, mirrored here so the contract surface for error envelopes is centrally readable.

Lint baseline preserved (4 errors / 5 warnings, all pre-existing) by adding `knot.json-contract-test/with-tmp` to `.clj-kondo/config.edn`'s `:lint-as` map (mirrors the existing `knot.store-test/with-tmp` entry).

Suite: 314 tests / 4055 assertions / 0 failures (was 296 / 2730 / 0; +18 deftests, +1325 assertions). Lint baseline unchanged.

Files: test/knot/json_contract_test.clj, .clj-kondo/config.edn, CHANGELOG.md.

Open question from the ticket description: "is this a v0.3 release blocker?" — addressed by the existence of the suite. Drift now fails `bb test`; pre-release CI / `bb test` will catch it. Not adding a blocker dep on kno-01kqgqfwk4h1 since the contract is now self-pinning.

**2026-05-05T17:15:26.654212401Z**

Added test/knot/json_contract_test.clj (1290 lines, 18 deftests, +1325 assertions). Pins the v0.3 --json contract end-to-end via subprocess: envelope invariants (schema_version + ok + data XOR error, with the documented knot check carve-out), per-command data shape for every --json read/mutating command, the four-vector-defaults contract on ticket payloads, meta.archived_to on close + status→terminal, and the four error envelopes (not_found / ambiguous_id with candidates / cycle with path / check exit-2 cannot-scan). Slice-8 surfaced a documented-but-non-obvious asymmetry — dep/undep `to` and unlink `to` are soft-resolved (broken refs land verbatim) while link resolves both sides strictly — now pinned positively in both directions so a future tightening would be a deliberate test update. Lint baseline preserved (4 errors / 5 warnings) via a one-line :lint-as entry in .clj-kondo/config.edn. Tests: 314/4055/0 (was 296/2730/0). CHANGELOG entry under [Unreleased]/Added describing the contract surface and what's pinned.
