---
id: kno-01kzxmazps09
title: update --status <terminal> archives the file but emits no meta.archived_to
status: closed
type: bug
priority: 2
mode: hitl
created: '2026-08-13T13:17:43.513700659Z'
updated: '2026-08-13T13:57:26.847599715Z'
closed: '2026-08-13T13:57:26.847599715Z'
tags:
- cli
- json
acceptance:
- title: The meta contract for terminal transitions is identical across close, status, and update — or update's divergence is a deliberate, documented refusal
  done: true
- title: references/json-protocol.md matches the shipped behaviour in both the 'meta slot' section and the per-command mutating table
  done: true
- title: A test pins whichever contract is chosen
  done: true
links:
- kno-01kzxp508wej
---

## Description

## Description

`knot close` and `knot status <id> <terminal>` both report the archive
destination in `meta.archived_to`. `knot update <id> --status <terminal>`
performs the *identical* archive routing but emits no `meta` slot at all.

Reproduced on v0.9.0 in a scratch project:

```
$ knot update <id> --status closed --json
{"ok":true, "data":{... "status":"closed" ...}, "meta":null}
$ ls .tickets/archive/
<id>--t.md          # the file did move
$ knot check
ok — scanned: live=0 archive=1
```

The defect is in the contract, not the routing: `references/json-protocol.md`
told consumers to "treat its absence as a hard signal that no archive routing
happened", which is false for exactly this call. A JSON consumer tracking
ticket file locations off `meta` silently loses the file.

## Design

Two defensible resolutions — this is why the ticket is `hitl`:

1. **Symmetry.** Emit `meta.archived_to` from `update` whenever the resulting
   status is terminal, matching `close` / `status`. Purely additive, no
   `schema_version` bump.
2. **Narrow the surface.** Keep `update` silent and make a terminal
   `--status` on `update` an explicit error pointing at `close`. Costs the
   one-call `--ac "<t>" --done --status closed` idiom, which the knot skill
   currently recommends.

Whichever wins, `references/json-protocol.md` needs the matching edit — it
currently documents the *observed* behaviour (archives, emits nothing) as an
exception to read carefully, which is accurate today but becomes stale the
moment this is resolved.

## Notes

**2026-08-13T13:57:26.847599715Z**

Resolved as symmetry: update --status <terminal> now emits meta.archived_to, matching close and status. Grilling session settled the prior question the slot had never answered — meta.archived_to is a LOCATION report ("this ticket is in the archive, here"), not a movement event, so it is keyed on the resulting status rather than on whether a file moved or which flags were passed. Consequences: a field-only update on an already-archived ticket now reports too (pinned deliberately); the un-archiving direction (reopen, status/update to non-terminal) stays silent with no restored_to counterpart, since data.status already carries that fact. Purely additive, schema_version stays 1.

Code: new private archive-meta helper in knot.cli computes the slot for every transition path; status-cmd and update-cmd both route through it. The drift was in the reporting (duplicated per-command), never in the routing (always shared in store/save!), so the helper is the seam that matches the defect. Rejected pushing it into store/save! — that would change a return shape 13 call sites consume to serve one.

Docs: references/json-protocol.md meta-slot section rewritten (the "exception to read carefully" paragraph deleted and replaced with a positive statement of what an absent meta means), envelope-key table row and the update/reopen rows in the per-command mutating table corrected; SKILL.md envelope paragraph names update. New ADR 0015 records location-report over movement-event with the four considered options. New CONTEXT.md "Archive routing" entry for the status-determines-location coupling that the whole decision rests on. CHANGELOG under Fixed.

Tests: meta-archived-to-contract-test in json_contract_test.clj went 9 -> 17 assertions — positive pin for update --status closed, positive pin for a field-only update on an archived ticket, negative pin for update --status open out of the archive, negative pin for a live field-only update. The false "update never archives" comment corrected there and in the two assertion messages in integration_test.clj and cli_test.clj (both assertions still held; only their stated reason was wrong). bb test 445/5208/0, clj-kondo 0 errors 0 warnings.

Out of scope, filed as kno-01kzxp508wej (linked): JSON path fields ship absolute paths while the docs show them repo-relative, unpinned because the contract test asserts str/includes? which passes for both forms.
