---
id: kno-01ktwst3dd8z
title: Add --parent filter to listing commands (list, ready, blocked, closed)
status: closed
type: feature
priority: 3
mode: afk
created: '2026-06-12T02:15:38.157178803Z'
updated: '2026-06-12T02:37:49.419091256Z'
closed: '2026-06-12T02:37:49.419091256Z'
tags:
- cli
- filtering
acceptance:
- title: knot list --parent <id> returns only tickets whose :parent equals the resolved id; repeatable values OR together
  done: true
- title: Partial ids resolve (live+archive); unresolved value dies in text mode and emits not_found/ambiguous JSON envelopes under --json
  done: true
- title: --parent works on ready, blocked, and closed, including an archived parent via closed
  done: true
- title: 'No none sentinel and no recursion: tickets without :parent never match'
  done: true
- title: Tests cover filtering, repeatability, resolution failures in both output modes, and the archived-parent case (bb test passes)
  done: true
- title: .claude/skills/knot/SKILL.md updated in the same commit; clj-kondo --lint src test clean
  done: true
---

## Description

Add a repeatable `--parent <id>` filter flag to `knot list`, `knot ready`, `knot blocked`, and `knot closed`, surfacing the direct Children of the given parent(s) per the CONTEXT.md composition glossary.

Contract (settled in design review, 2026-06-12):

- **Resolution**: each `--parent` value goes through the standard partial-id resolution (live+archive). A value that does not resolve fails loudly: under `--json`, emit the same `not_found` / `ambiguous` envelopes that `knot show` uses (with candidate list on ambiguity); otherwise plain stderr die, exit 1.
- **Repeatable**: values collect into a set like every other list filter; a ticket matches when its `:parent` equals any resolved id.
- **Semantics**: direct children only — flat equality on the `:parent` frontmatter field after resolution. No recursive descendants, no `none` sentinel for orphans.
- **Output unchanged**: `parent` is already present in the JSON row when set; the human table is untouched. The filter is the entire feature.
- **Scope**: the four listing commands only — not `prime`.

Note: `ready --parent` filters the already-computed ready set; it does not change what counts as ready (consistent with ADR 0003 — parent/children gate status transitions, not readiness).

## Design

The criterion belongs in the shared filter layer: a `:parent` arm in `query/match-criterion?` (plain set lookup on the canonical id), `:parent` added to the projected keys in `main/filter-opts-from-cli`, with resolution happening in the handlers before projection — this is the only filter whose values resolve. Flag declared on the four commands in `help.clj` with `:coerce []` plus an example. The envelope helpers (`emit-not-found-envelope!`, `emit-ambiguous-envelope!`) already exist in main.clj — reuse them.

## Notes

**2026-06-12T02:37:49.419091256Z**

Added repeatable --parent <id> filter to list/ready/blocked/closed: a :parent equality arm in query/match-criterion?, the key added to both filter-projection lists (main/filter-opts-from-cli and cli/filter-criteria), and handler-side partial-id resolution (live+archive) that fails loudly — stderr die in text mode, not_found/ambiguous_id envelopes under --json. Direct children only; no recursion or orphan sentinel.
