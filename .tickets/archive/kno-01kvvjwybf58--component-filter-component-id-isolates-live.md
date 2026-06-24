---
id: kno-01kvvjwybf58
title: 'Component filter: --component <id> isolates live-induced cluster on list/ready/blocked'
status: closed
type: task
priority: 2
mode: afk
created: '2026-06-24T01:11:18.627124878Z'
updated: '2026-06-24T01:28:21.684761405Z'
closed: '2026-06-24T01:28:21.684761405Z'
tags:
- filter
- query
acceptance:
- title: query primitive returns live-induced single-seed member-set including singleton {seed}; closed/broken refs non-conductive
  done: true
- title: --component on list/ready/blocked filters to the seed's live component; composes with --tag/--type/--mode/--limit; membership filter-independent
  done: true
- title: closed seed → fail-fast error; --component + --closure together → fail-fast error; ordinal not accepted as seed
  done: true
- title: --component absent from closed; CC column/JSON shapes byte-unchanged
  done: true
- title: bb test green + clj-kondo clean; CONTEXT.md note + SKILL.md updated in the same commit as the code
  done: true
links:
- kno-01kvvf1z9etb
---

## Description

Add --component <id> to list/ready/blocked: admit exactly the tickets in the same live-induced connected component as the seed (all axes :parent∪:deps∪:links, undirected, closed non-conductive). The action-companion to the CC column (ADR 0013): the column reveals live islands, --component isolates one. Distinct from --closure (corpus-wide, closed-conducting, --via-tunable, multi-seed) — see ADR 0014 for the full distinction and the closed-bridge argument that forbids implementing it as a --live mode of --closure.

Shape (fixed, per ADR 0014): single id, id-only (never ordinal), seed must be live (closed seed = fail-fast error), no --via (all-axes), mutually exclusive with --closure (fail-fast guard). Membership computed over full live corpus, intersected before filter-tickets, --limit last. CC column left untouched (renders constant global ordinal; presence-gate hides for singleton seed). Not on closed (archive has no live components).

Riding branch feat/cc-connected-components.

## Design

Deliverable chain (ADR 0014 = docs/adr/0014-component-filter-live-induced-cluster.md):
1. query primitive (e.g. live-component): live-restricted single-seed flood over :parent∪:deps∪:links, returns member id-set INCLUDING singleton {seed}. closure-set and connected-components untouched.
2. cli.clj: component-filter beside closure-filter; closed-seed liveness check produces the fail-fast error.
3. main.clj: resolve-component-filter! mirroring resolve-closure-filter! (single-id partial resolution); wire into list/ready/blocked; reject --component + --closure together at parse.
4. tests (bb test green) + lint (clj-kondo --lint src test clean).
5. CONTEXT.md glossary note (Component filter) + SKILL.md update in the SAME commit (AGENTS.md skill-sync rule).

## Notes

**2026-06-24T01:28:21.684761405Z**

Implemented --component <id> on list/ready/blocked per ADR 0014. New query/live-component primitive: live-induced single-seed flood over :parent∪:deps∪:links returning the member-set including singleton {seed}, closed non-conductive, broken refs dropped. cli/component-filter intersects before display filters (closed seed = fail-fast); main/resolve-component-filter! does single partial-id resolution + --component/--closure mutual-exclusion guard; not on closed. CC column/JSON byte-unchanged.
