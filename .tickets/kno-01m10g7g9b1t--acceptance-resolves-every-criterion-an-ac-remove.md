---
id: kno-01m10g7g9b1t
title: acceptance resolves every criterion an --ac / --remove-ac value addresses
status: open
type: chore
priority: 3
mode: afk
created: '2026-08-27T02:19:20.234851129Z'
updated: '2026-08-27T02:19:20.234851129Z'
tags:
- depth-review
acceptance:
- title: acceptance exposes one resolver returning every index an ordinal or title addresses; the single-index resolver is defined in terms of it and ordinal? is private
  done: false
- title: cli's --remove-ac resolver is deleted; flip and remove both read acceptance's resolver
  done: false
- title: update --ac / --remove-ac behaviour, messages and exit codes are unchanged (existing cli and integration tests pass untouched)
  done: false
- title: bb test and clj-kondo clean
  done: false
---

## Description

The ordinal-or-title reading of an --ac value was put in acceptance, but --remove-ac needed every title match rather than the first, so cli grew its own resolver: it branches on acceptance's now-public ordinal? predicate and re-implements the title scan. The discrimination rule has leaked out of the module that owns it.

Give acceptance one resolver that returns the full set of matching indices — one for an ordinal, every title match otherwise. The flip takes the first; the removal drops them all; ordinal? goes private again and cli's resolver is deleted. update --ac / --remove-ac behaviour is unchanged, including the not-found error and the all-digits-title rule.
