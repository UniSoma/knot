---
id: kno-01kvdrjb5fwa
title: Umbrella progress rollup in listings + show (CHLD column)
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-06-18T16:21:00.719625945Z'
updated: '2026-06-18T18:02:16.782870112Z'
closed: '2026-06-18T17:10:27.734796233Z'
acceptance:
- title: query-side children-progress fn returns [terminal total] for direct children, mirroring acceptance/progress
  done: true
- title: CHLD column (header CHLD, d/t, '-' for non-umbrellas) conditionally spliced into ls table only when result set has >=1 umbrella; applies to list/ready/blocked/closed
  done: true
- title: 'show human view: ''## Children (d/t)'' heading; per-child list unchanged'
  done: true
- title: show --json and list --json emit children_total/children_terminal on umbrella rows only
  done: true
- title: terminal counts Won't-do closures (reuse existing terminal-statuses set)
  done: true
- title: .claude/skills/knot/SKILL.md updated for new column + json fields in same commit
  done: true
links:
- kno-01kvdybmymby
---

## Description

Surface umbrella progress (terminal/total of direct children) in listings and show. Design settled in docs/adr/0009; glossary term 'Umbrella progress' in CONTEXT.md. Umbrella-ness = has children via :parent (NOT the epic type). Mirror the AC column mechanics (conditional splice, d/t form). Closed children live in archive, so progress is a full-corpus (live+archive) computation.

## Notes

**2026-06-18T17:10:27.734796233Z**

Umbrella progress rollup shipped: query/children-progress [terminal total] over full corpus; CHLD column conditionally spliced into list/ready/blocked/closed; '## Children (d/t)' on show; children_total/children_terminal on umbrella JSON rows only. Per ADR 0009.
