---
id: kno-01kvgxx219vj
title: 'Closure filter: --closure on list/ready/closed/blocked'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-06-19T21:51:57.993202572Z'
updated: '2026-06-19T22:40:53.964789258Z'
closed: '2026-06-19T22:40:53.964789258Z'
tags:
- listing
- relationships
acceptance:
- title: query/closure-set primitive with tests (single/multi-seed union, cycles, broken refs, --via narrowing, archive traversal)
  done: true
- title: --closure + --via wired on list, ready, closed, blocked
  done: true
- title: Partial-id resolution + not_found/ambiguous_id JSON envelopes per seed (inherits --parent pattern)
  done: true
- title: Empty seed rejected at parse; isolated seed yields silent empty/singleton
  done: true
- title: 'Composition tests: --closure X --type bug; --status open; --limit N; --json'
  done: true
- title: 'ADR 0010 records: closure-as-filter, undirected+transitive default, all-axes default, plain output (rejected alternatives named)'
  done: true
- title: .claude/skills/knot/SKILL.md updated (flag rows + examples)
  done: true
- title: --help updated for list/ready/closed/blocked
  done: true
- title: CHANGELOG.md + docs/agents/issue-tracker.md mention the flag
  done: true
---

## Description

Add `--closure <id>[,<id>…]` to the four listing commands (`list`, `ready`, `closed`, `blocked`) so users can filter tickets by membership in the undirected transitive closure of one or more seed ids over the `:parent`, `:deps`, and `:links` axes.

## Semantics

- **Undirected transitive closure.** From each seed, walk every `:parent`/`:deps`/`:links` edge in both directions, recursively. Seed is included in the result set.
- **Multi-seed = union.** `--closure id1,id2` is `closure(id1) ∪ closure(id2)`.
- **Computed on the full corpus** (archive included). Membership is graph-faithful regardless of status; each command's normal display filter (default non-terminal for `list`, terminal for `closed`, etc.) governs what's *shown*.
- **`--via parent,deps,links`** narrows participating axes (default: all three).
- **Composes** with `--type`, `--status`, `--tag`, `--mode`, `--priority`, `--assignee`, `--limit`, `--json`. Closure stage runs first on the full corpus; `query/filter-tickets` runs after.
- **Output is plain** — no `VIA` / `DIST` column, no extra JSON fields. Filter, not visualization.

## Surface

    knot list   --closure kno-01abc [--via parent,deps]
    knot ready  --closure kno-01abc
    knot closed --closure kno-01abc
    knot blocked --closure kno-01abc

Help text:

    --closure <id>[,<id>…]  Filter to tickets in the undirected transitive
                            closure of the seed(s) over parent, deps, and links.
    --via <axes>            Restrict closure to listed axes (any of:
                            parent, deps, links). Default: all three.

## Error / edge-case behavior (inherits existing patterns)

- Partial-id resolution per seed; `not_found` / `ambiguous_id` JSON envelopes match `--parent` (see `resolve-parent-filter!` in main.clj:414).
- Fail-fast on first unresolvable seed.
- Cycles: visited-set guard (same pattern as `query/dep-tree`, `query/project-cycles`).
- Broken refs: silently dropped from the membership set (consistent with `dep-tree` / `project-cycles`).
- Empty seed string: rejected at parse.
- Isolated seed: empty/singleton result, no warning.

## Implementation sketch

- `query/closure-set [tickets seed-ids axes] -> set<id>` — BFS over undirected graph; ~20 lines. Reuses `index-by-id`.
- Each listing command (`ls-cmd`, `ready-cmd`, `closed-cmd`, `blocked-cmd`) gains a closure stage applied to the full corpus *before* `filter-tickets`.
- `main.clj`: add `--closure` / `--via` to the four listing specs; factor out `resolve-parent-filter!` body as a shared `resolve-id-list!` helper.

## Design rationale (from grilling session grill::closure)

Each of the following had a real alternative considered and rejected:

1. **Closure-as-filter, not a new command.** `--closure` composes for free with `--type`/`--status`/`--limit`/`--json`; a `knot closure <id>` command would re-derive that surface. Exploration belongs in existing surfaces (`show`, `dep tree`).
2. **Undirected default.** "All tickets related to it" reads undirected. `dep tree <id>` already covers the directed-out `:deps` walk.
3. **All three axes by default, `--via` to narrow.** Matches user phrasing ("parent, children, linked, dep etc"). One restriction flag is cheaper than three negative flags; `:links` is the noisiest axis so users get an escape hatch.
4. **Plain output.** Filter, not explorer. Annotations (VIA/DIST) raise tie-break questions (multi-path, multi-seed) and bloat an already-dense table.
5. **Full-corpus traversal + per-command display filter.** Mandatory for graph correctness; "live-only graph" would violate closure properties.
6. **Multi-seed union.** Consistent with `--parent id1,id2` semantics. Intersection deferred until asked.

## Companion vocabulary

CONTEXT.md gains a "Closure" glossary entry under Ticket relationships (already drafted in the same design-capture as this ticket).

## Notes

**2026-06-19T21:54:04.746583505Z**

ADR 0010 (docs/adr/0010-closure-filter-on-listings.md) and the CONTEXT.md Closure glossary entry are already drafted from grilling session grill::closure. Implementation work doesn't need to author them — only keep them in sync if a decision moves during build.

**2026-06-19T22:40:53.964789258Z**

Shipped --closure <id>[,<id>…] (+ --via) on list/ready/blocked/closed. New query/closure-set primitive: undirected transitive closure over :parent/:deps/:links, BFS with cycle guard, broken refs dropped, multi-seed union; computed on full corpus so each command's display filter still governs output. Seeds resolve like --parent (not_found/ambiguous_id envelopes). Factored resolve-id-list! shared by both filters. Plain output, JSON shape unchanged. Per ADR 0010.
