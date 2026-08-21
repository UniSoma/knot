---
id: kno-01m0jbrv9mtn
title: Document the computed listing columns in knot help list/ready/blocked
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-21T14:32:03.636537976Z'
updated: '2026-08-21T15:55:29.067017765Z'
closed: '2026-08-21T15:55:29.067017765Z'
parent: kno-01m0jbrv6wbe
tags:
- docs
- help
acceptance:
- title: knot help list, ready, and blocked each explain every computed column they render and its --json field name
  done: true
- title: The wording matches CONTEXT.md (live-induced scope, cone vs degree vs component) without restating the ADRs
  done: true
- title: The skill's listing-filters-and-columns.md no longer duplicates what help now says; it keeps only the judgment
  done: true
---

## Description

The listing tables render CC, AGE, AC, CHLD, LEV, CPL and --json emits cc, leverage, coupling, children_total/children_terminal — but knot help list, ready, and blocked define none of them. CONTEXT.md, ADRs 0011–0013, and the skill's references/listing-filters-and-columns.md do, which breaks the pull-surface rule (ADR 0017): anything derivable from the CLI belongs in help.

Fix: a :notes block on list/ready/blocked naming each column, its JSON field, one-line meaning, and the live-induced scope. This is also where the LVL column (sibling ticket) lands.

## Notes

**2026-08-21T15:55:29.067017765Z**

A shared listing-column-notes block on list/ready/blocked now defines AGE, AC, CHLD, LEV, CPL and CC with their --json field names, opening with the live-induced rule stated once. Wording follows the CONTEXT.md glossary (forward unblocking cone vs undirected 1-hop degree vs component membership) without restating ADR mechanics. A help_test guard derives the rendered column set by shape from knot.output's ls-* vars rather than a hardcoded list, so a future column with no note fails the build — verified by interning a synthetic ls-lvl-column and watching it go red, which is what will hold kno-01m0jbrvd2be to its LVL note. The skill's duplicated Columns inventory is gone, keeping only the judgment; SKILL.md carried the same inventory and was corrected with it. No note added for closed (outside the criteria). git:724fea4
