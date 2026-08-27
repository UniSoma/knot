---
id: kno-01m10g7qx4tc
title: 'Reshape main''s error paths: split id resolution from its exit policy, one gate-failure emitter'
status: open
type: chore
priority: 2
mode: afk
created: '2026-08-27T02:19:28.036513166Z'
updated: '2026-08-27T02:19:28.036513166Z'
tags:
- depth-review
acceptance:
- title: One pure resolve-ids mapping and one exit-1 wrapper; prime --parent uses the mapping and the second resolver is deleted
  done: false
- title: The emit-*! gate-failure family is one emitter parameterised by code and extra fields; the handlers' catch chains call it
  done: false
- title: Every error envelope and stderr message is byte-identical to before (json_contract_test and integration_test pass untouched)
  done: false
- title: main.clj is shorter than it was before the body/listing set landed (under 1459 lines)
  done: false
- title: bb test and clj-kondo clean
  done: false
---

## Description

main grew past its already-failing locality size in the last set of changes, in two places that each added a sibling of something that already existed.

Id resolution: prime --parent needed the resolve-every-id mapping without the exit-1 policy that list/ready/blocked/closed want, so it got a second resolver that differs only in what happens on failure. Split the existing one into the pure mapping (resolve or throw) and the exit-policy wrapper; prime uses the former; the duplicate goes.

Gate failures: already_assigned became the seventh emit-*! function of the same shape — a JSON error envelope with a code and extra fields, or a stderr line, then exit 1. Collapse the family into one emitter taking the command name, the code, the message and the extra fields, and have every handler's catch chain call it.

Every error envelope and every stderr line stays byte-identical; the json-contract and integration tests pin them. The measure of done is main back under its size before the set landed.
