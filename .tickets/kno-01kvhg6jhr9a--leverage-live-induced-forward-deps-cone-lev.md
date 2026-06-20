---
id: kno-01kvhg6jhr9a
title: 'Leverage: live-induced forward deps cone (LEV column + JSON) on list/ready/blocked'
status: open
type: task
priority: 2
mode: afk
created: '2026-06-20T03:11:44.184346537Z'
updated: '2026-06-20T03:54:52.192400745Z'
tags:
- leverage
- graph
- query
acceptance:
- title: 'query primitive returns the live-induced transitive forward :deps cone count: a closed intermediary severs the cone, cycles are guarded, broken refs are dropped'
  done: false
- title: list/ready/blocked render a LEV column and emit a leverage integer in --json; closed and non-listing commands are byte-unchanged
  done: false
- title: tests cover keystone cone size, live-induced severing by a closed node, deps-leaf with large cone, broken-ref drop, and cycle guard
  done: false
- title: SKILL.md documents the LEV column and leverage JSON field
  done: false
- title: bb test and clj-kondo --lint src test are clean
  done: false
links:
- kno-01kvhjnhxcam
---

## Description

Add a structural **leverage** metric per ADR 0011: the count of live tickets that transitively depend on a row through :deps, computed over the live-induced deps subgraph (closed nodes are non-conductive), the row itself excluded. Always computed, always shown as a LEV column and a `leverage` integer in --json, on list/ready/blocked only. No flag, no sort, not on closed in v1. See docs/adr/0011-leverage-live-induced-deps-cone.md and the Leverage entry in CONTEXT.md.

## Design

- New query.clj primitive: the transitive, live-only sibling of `blocking` (which returns direct dependents only). Build the reverse-:deps adjacency over live tickets, BFS per row with a visited-set cycle guard, drop broken refs — same conventions as closure-set/dep-tree/project-cycles.
- Wire a LEV column into list/ready/blocked table rendering; add a `leverage` integer to their --json. Leave closed/show/dep-tree/delete/check untouched.
- Keep default output of closed and all non-listing commands byte-identical.
- Sync .claude/skills/knot/SKILL.md (LEV column + leverage JSON field) in the same commit.

Deferred to v2 (out of scope, additive, no ADR revisit): --sort leverage; knot.el row heat-coloring; knot serve node-colored graph.
