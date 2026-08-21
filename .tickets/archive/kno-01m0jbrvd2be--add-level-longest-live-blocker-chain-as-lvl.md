---
id: kno-01m0jbrvd2be
title: 'Add level: longest live-blocker chain, as LVL column and JSON field'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:03.731877234Z'
updated: '2026-08-21T16:26:42.626765375Z'
closed: '2026-08-21T16:26:42.626765375Z'
parent: kno-01m0jbrv6wbe
tags:
- graph
- listing
acceptance:
- title: level is 0 for every ready ticket and >= 1 for every blocked ticket, including broken-ref blockers
  done: true
- title: 'A closed intermediary severs the chain: A deps B deps C with B closed gives A level 0'
  done: true
- title: Members of a live deps cycle get level null and render '-'
  done: true
- title: LVL column and level field appear on list, ready, blocked and not on closed
  done: true
- title: knot help list/ready/blocked notes, the skill references file, CONTEXT.md and CHANGELOG describe level in the same commit
  done: true
deps:
- kno-01m0jbrv9mtn
external_refs:
- git:c624b1d
---

## Description

Per CONTEXT.md Level: for a live ticket, the length of the longest chain of live blockers beneath it over the live-induced :deps subgraph. 0 iff ready. Whole-graph, not umbrella-scoped (a blocker outside the parent still gates). Closed intermediaries sever the chain as in leverage. Missing referent = live leaf blocker (level >= 1). Member of a live deps cycle = null, rendered '-'.

Surface, per the ADR 0011 precedent: always-on LVL column and level JSON field on list/ready/blocked; never on closed; no flag, no sort, no standalone waves command. An orchestrator gets its waves with knot blocked --parent X --json | group_by(.level).

Implement next to leverage in query.clj (reverse of the cone walk: longest path over live deps, memoised, cycle-guarded). Surfaces move in the same commit: help :notes (from the parent's first child), skill references, CHANGELOG.

## Notes

**2026-08-21T16:26:42.626765375Z**

query/levels is one memoised DFS over the whole corpus returning {id -> level}, following connected-components rather than leverage's per-row rebuild; the memo is written only after a subtree is fully explored, so cached values are path-independent. Closed intermediaries sever the chain, a missing referent counts as a live leaf blocker (level 1, not 0), and level is whole-graph — a blocker outside the parent still gates. Cycle members get nil rendered '-'; so does a ticket depending THROUGH a live cycle, since maxing over only the numeric children would hand it a wrong number. Second judgment call: on knot list --status closed the LVL cell is '-' where LEV/CPL print numbers, because a closed ticket is not a node of the live-induced subgraph. Both are documented in the query docstring, help :notes and json-protocol.md. The drift guard installed by kno-01m0jbrv9mtn went red on the new column as designed and was satisfied, not weakened.
