---
id: kno-01m0jbrv9mtn
title: Document the computed listing columns in knot help list/ready/blocked
status: open
type: bug
priority: 2
mode: afk
created: '2026-08-21T14:32:03.636537976Z'
updated: '2026-08-21T14:32:03.636537976Z'
parent: kno-01m0jbrv6wbe
tags:
- docs
- help
acceptance:
- title: knot help list, ready, and blocked each explain every computed column they render and its --json field name
  done: false
- title: The wording matches CONTEXT.md (live-induced scope, cone vs degree vs component) without restating the ADRs
  done: false
- title: The skill's listing-filters-and-columns.md no longer duplicates what help now says; it keeps only the judgment
  done: false
---

## Description

The listing tables render CC, AGE, AC, CHLD, LEV, CPL and --json emits cc, leverage, coupling, children_total/children_terminal — but knot help list, ready, and blocked define none of them. CONTEXT.md, ADRs 0011–0013, and the skill's references/listing-filters-and-columns.md do, which breaks the pull-surface rule (ADR 0017): anything derivable from the CLI belongs in help.

Fix: a :notes block on list/ready/blocked naming each column, its JSON field, one-line meaning, and the live-induced scope. This is also where the LVL column (sibling ticket) lands.
