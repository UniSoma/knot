---
id: kno-01kqwgeba7jz
title: JSON contract test suite — pin every --json command's shape against runtime
status: open
type: task
priority: 2
mode: afk
created: '2026-05-05T16:44:50.374838263Z'
updated: '2026-05-05T16:45:33.373728267Z'
tags:
- v0.3
- json
- testing
- cli
acceptance:
- title: 'Envelope invariants pinned in one place: schema_version=1, ok always present, data XOR error (with the documented knot check exception where ok:false may coexist with data).'
  done: false
- title: Per-command data shape asserted for each --json command (key presence + types) — list, show, ready, blocked, closed, prime, info, check, dep tree, plus the mutators create, start, status, close, reopen, dep, undep, link, unlink, add-note, update, migrate-ac.
  done: false
- title: 'Vector-default contract pinned: tags/deps/links/external_refs always arrays in ticket payloads (read + mutating envelopes via touched-ticket-json / touched-tickets-json).'
  done: false
- title: meta.archived_to pinned for close --json and any status transition to a terminal status.
  done: false
- title: 'Error envelopes pinned: not_found (any id-resolving command), ambiguous_id with candidates array, cycle with error.cycle path on dep --json, check exit-2 cannot-scan envelope.'
  done: false
- title: Tests run on bb test; lint baseline unchanged.
  done: false
- title: CHANGELOG entry under [Unreleased].
  done: false
links:
- kno-01kqgqegm782
- kno-01kqts0qxbvx
---

## Description

Add test/knot/json_contract_test.clj (or similar) that pins the runtime JSON envelope shape for every --json command. Replaces the snapshot-test conformance AC from kno-01kqgqegm782 (closed won't-do) — the drift-detection benefit, separated from the abandoned `knot schema` CLI surface.

Coverage target: every --json command, happy path + at least one error path each. Envelope invariants asserted once; per-command `data` shape asserted per command.

Open question for the ticket-grabber: is this a v0.3 release blocker (add as blocker on kno-01kqgqfwk4h1) or a v0.4 follow-up? Default position: blocker — drift in the JSON protocol is exactly the regression class v0.3 sets out to avoid.
