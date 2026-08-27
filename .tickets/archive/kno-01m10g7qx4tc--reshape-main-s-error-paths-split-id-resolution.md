---
id: kno-01m10g7qx4tc
title: 'Reshape main''s error paths: split id resolution from its exit policy, one gate-failure emitter'
status: closed
type: chore
priority: 2
mode: afk
created: '2026-08-27T02:19:28.036513166Z'
updated: '2026-08-27T12:17:52.891587291Z'
closed: '2026-08-27T12:17:52.891587291Z'
tags:
- depth-review
- settled
acceptance:
- title: One pure resolve-ids mapping and one exit-1 wrapper; prime --parent uses the mapping and the second resolver is deleted
  done: true
- title: The emit-*! gate-failure family is one emitter parameterised by code and extra fields; the handlers' catch chains call it
  done: true
- title: Every error envelope and stderr message is byte-identical to before (json_contract_test and integration_test pass untouched)
  done: true
- title: main.clj is shorter than it was before the body/listing set landed (under 1459 lines)
  done: true
- title: bb test and clj-kondo clean
  done: true
---

## Description

main grew past its already-failing locality size in the last set of changes, in two places that each added a sibling of something that already existed.

Id resolution: prime --parent needed the resolve-every-id mapping without the exit-1 policy that list/ready/blocked/closed want, so it got a second resolver that differs only in what happens on failure. Split the existing one into the pure mapping (resolve or throw) and the exit-policy wrapper; prime uses the former; the duplicate goes.

Gate failures: already_assigned became the seventh emit-*! function of the same shape — a JSON error envelope with a code and extra fields, or a stderr line, then exit 1. Collapse the family into one emitter taking the command name, the code, the message and the extra fields, and have every handler's catch chain call it.

Every error envelope and every stderr line stays byte-identical; the json-contract and integration tests pin them. The measure of done is main back under its size before the set landed.

### Decisions

- The emitter family is the four dual-mode gate emitters in `main.clj` — `emit-acceptance-incomplete!`, `emit-already-assigned!`, `emit-open-children!`, `emit-has-incoming-refs!` — not all seven `emit-*!` fns. `emit-error-envelope!` stays as the JSON primitive the new emitter delegates to; `emit-not-found-envelope!` and `emit-ambiguous-envelope!` are JSON-only, thin, and widely called, so they stay as they are.
- The per-code stderr text lives inside the one emitter, dispatched by code (`case`), never built at call sites. Three of the codes are raised from two catch chains (`transition-handler` and `update-handler`); call-site rendering would duplicate the gate text. The emitter takes the command name, `json?`, the code, the message, and the extra fields pre-shaped for JSON (the stderr branch reads what it needs from them, including the open-children `:gate`).
- `check-cannot-scan!` and `info-emit-error!` stay separate: check's exit-2 contract and info's prefix are not part of this family, and parameterising the exit code is not worth blurring that contract.
- Everything stays in `main.clj`. The line target (under 1459) is met by deleting duplication, not by relocating code to a new namespace; if the two collapses land short, consolidate further in main.
- Id resolution splits into a pure `resolve-ids` (ctx, raws → vector of full ids; `store/resolve-id` already throws on not-found/ambiguous) and `resolve-id-list!` as its exit-1 wrapper. `resolve-parent-filter!` keeps its signature and keeps calling the wrapper. `prime-handler` inlines the three-line `:parent` assoc over `resolve-ids` — its existing outer `catch` is the always-exit-0 policy — and `resolve-prime-parent-filter` is deleted whole. No resolver-fn parameter on `resolve-parent-filter`.

## Notes

**2026-08-27T12:17:52.891587291Z**

Split id resolution into the pure resolve-ids mapping and the exit-1 resolve-id-list! wrapper; prime --parent uses the mapping and resolve-prime-parent-filter is deleted. The four dual-mode gate emitters collapsed into emit-gate-failure! (cmd-name, json?, code, message, JSON-shaped extras), with one case on the code yielding the stderr suffix, item lines and footer. link/unlink shared a byte-identical catch chain, so its --json routing became emit-json-failure! — the third collapse the Decisions sanctioned to reach the line target. Verified byte-identical output across 36 gate and id-resolution scenarios in both modes against the previous build; 487 tests and clj-kondo clean; main.clj 1457 lines. Commit 4976ae9.
