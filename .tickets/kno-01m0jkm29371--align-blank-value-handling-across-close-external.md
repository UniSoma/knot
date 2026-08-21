---
id: kno-01m0jkm29371
title: Align blank-value handling across close --external-ref and update --add-external-ref
status: open
type: bug
priority: 3
mode: afk
created: '2026-08-21T16:49:15.554861067Z'
updated: '2026-08-21T16:49:15.554861067Z'
tags:
- close
- update
- consistency
acceptance:
- title: 'close --external-ref "" and update --add-external-ref "" agree: both exit 1 with invalid_argument, and neither writes'
  done: false
- title: The close path reports the error through the same die / JSON envelope shape update already uses, not as an uncaught exception
  done: false
- title: start, close and the generic status command keep their existing exit codes and envelopes for every other flag
  done: false
- title: Help for close and update states that a blank external ref is rejected
  done: false
---

## Description

A blank value is rejected on one path and silently dropped on the other, for a structural reason rather than a decided one.

`knot update <id> --add-external-ref ""` throws `invalid_argument`: `update-handler` normalizes the delta values inside its `try`, so `normalize-delta-values` can blank-reject and the catch turns it into a `die` or a `{ok:false, error:{code:"invalid_argument", ...}}` envelope.

`knot close <id> --summary ... --external-ref ""` silently no-ops: `transition-handler` builds its opts map *outside* its `try`, so a throw there would escape as an uncaught exception rather than a clean error. The blank is dropped with `remove str/blank?` instead.

Neither behaviour was chosen on the merits — the ticket that added both flags (kno-01m0jbrvpbg5) left blank handling unspecified, and the split fell out of where each handler happens to build its opts. Two flags that record the same field should answer a blank the same way.

The fix is to decide which answer is right and make both paths give it. Rejecting is the better default (it matches every other value flag and a blank ref is always a mistake), which means moving the close-path normalization inside `transition-handler`'s `try` so it can throw cleanly.

## Design

Touch points: `main.clj` `transition-handler` (the opts* build, currently ahead of the try) and `normalize-delta-values`. `cli.clj` `apply-list-deltas` is shared by both paths and should not need changing.

Watch the blast radius on the close path: `transition-handler` also serves `start` and the generic `status` command, so moving work inside the try must not change how any other flag on those commands reports an error. Pin the existing exit codes before moving anything.
