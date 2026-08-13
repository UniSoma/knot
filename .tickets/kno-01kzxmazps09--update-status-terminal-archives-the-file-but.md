---
id: kno-01kzxmazps09
title: update --status <terminal> archives the file but emits no meta.archived_to
status: open
type: bug
priority: 2
mode: hitl
created: '2026-08-13T13:17:43.513700659Z'
updated: '2026-08-13T13:17:43.513700659Z'
tags:
- cli
- json
acceptance:
- title: The meta contract for terminal transitions is identical across close, status, and update — or update's divergence is a deliberate, documented refusal
  done: false
- title: references/json-protocol.md matches the shipped behaviour in both the 'meta slot' section and the per-command mutating table
  done: false
- title: A test pins whichever contract is chosen
  done: false
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
