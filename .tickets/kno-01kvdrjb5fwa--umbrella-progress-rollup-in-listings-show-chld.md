---
id: kno-01kvdrjb5fwa
title: Umbrella progress rollup in listings + show (CHLD column)
status: open
type: feature
priority: 2
mode: hitl
created: '2026-06-18T16:21:00.719625945Z'
updated: '2026-06-18T16:21:00.719625945Z'
acceptance:
- title: query-side children-progress fn returns [terminal total] for direct children, mirroring acceptance/progress
  done: false
- title: CHLD column (header CHLD, d/t, '-' for non-umbrellas) conditionally spliced into ls table only when result set has >=1 umbrella; applies to list/ready/blocked/closed
  done: false
- title: 'show human view: ''## Children (d/t)'' heading; per-child list unchanged'
  done: false
- title: show --json and list --json emit children_total/children_terminal on umbrella rows only
  done: false
- title: terminal counts Won't-do closures (reuse existing terminal-statuses set)
  done: false
- title: .claude/skills/knot/SKILL.md updated for new column + json fields in same commit
  done: false
---

## Description

Surface umbrella progress (terminal/total of direct children) in listings and show. Design settled in docs/adr/0009; glossary term 'Umbrella progress' in CONTEXT.md. Umbrella-ness = has children via :parent (NOT the epic type). Mirror the AC column mechanics (conditional splice, d/t form). Closed children live in archive, so progress is a full-corpus (live+archive) computation.
