---
id: kno-01kvhjnhxcam
title: 'Coupling: live-induced :deps+:links degree (CPL column + JSON) on list/ready/blocked'
status: open
type: task
priority: 2
mode: afk
created: '2026-06-20T03:54:52.192400745Z'
updated: '2026-06-20T03:54:52.192400745Z'
tags:
- coupling
- graph
- query
acceptance:
- title: 'query primitive returns the live-induced undirected :deps∪:links distinct-neighbor count: parent edges excluded, both deps directions and links counted, neighbors deduped across axes, closed neighbors excluded, broken refs dropped'
  done: false
- title: list/ready/blocked render a CPL column and emit a coupling integer in --json; closed and non-listing commands are byte-unchanged
  done: false
- title: 'tests cover: deps+links union dedup (a pair joined by both a dep and a link counts once), both-direction deps counted, parent edges excluded, closed-neighbor excluded, broken-ref drop'
  done: false
- title: SKILL.md documents the CPL column and coupling JSON field
  done: false
- title: bb test and clj-kondo --lint src test are clean
  done: false
links:
- kno-01kvhg6jhr9a
---

## Description

Add a structural **coupling** metric per ADR 0012: the count of distinct live tickets a row is directly connected to through :deps (either direction) or :links, computed over the live-induced graph (closed neighbors excluded), :parent excluded, deduped across axes. Always computed, always shown as a CPL column and a `coupling` integer in --json, on list/ready/blocked only. No flag, no sort, not on closed in v1. A diagnostic "where is the complexity" heatmap for cognitive-load reduction. See docs/adr/0012-coupling-live-induced-deps-links-degree.md and the Coupling entry in CONTEXT.md.

## Design

- New query.clj primitive: build the undirected :deps ∪ :links adjacency restricted to live tickets (forward-:deps ∪ reverse-:deps via the existing `inverses` ∪ :links), count distinct live neighbors per row. Reuse closure-set adjacency machinery axis-filtered to deps+links; drop broken refs. Cheaper than leverage — 1-hop, no transitive walk, no cycle guard.
- Wire a CPL column into list/ready/blocked table rendering; add a `coupling` integer to their --json. Leave closed/show/dep-tree/delete/check untouched.
- Keep default output of closed and all non-listing commands byte-identical.
- Sync .claude/skills/knot/SKILL.md (CPL column + coupling JSON field) in the same commit.

Deferred to v2 (out of scope, additive, no ADR revisit): articulation-point flag (prescriptive cut-finder); local-clustering refinement for degree star-blindness; --sort coupling; knot.el row heat-coloring.
