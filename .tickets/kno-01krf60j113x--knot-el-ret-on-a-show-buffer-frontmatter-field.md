---
id: kno-01krf60j113x
title: 'knot.el: RET on a show-buffer frontmatter field opens the matching update prompt'
status: in_progress
type: feature
priority: 3
mode: afk
created: '2026-05-12T22:48:06.944937966Z'
updated: '2026-05-13T00:29:15.890355944Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: RET on a frontmatter value span (status/type/priority/mode/assignee, tag list, parent) opens the matching knot-update-set-* flow
  done: true
- title: knot-show--render annotates each editable value span with a 'knot-field <symbol> text property
  done: true
- title: 'id-button drill-in keeps RET precedence on lines where the value is a buttonized id (e.g. parent: <id>) — edit fires only when no id button at point'
  done: true
- title: AC line flip keeps RET precedence on AC rows (existing knot-show-flip-ac path)
  done: true
- title: Tag list edits as a whole via knot-update-set-tags (no per-tag editing)
  done: true
- title: Non-editable spans (id, created, updated, deps/links and the relationship sections) do not trigger edit on RET
  done: true
- title: Existing , transient and ,<key> suffixes continue to work — RET-to-edit is additive, not a replacement
  done: true
- title: byte-compile clean under Emacs 28.1; no regressions in existing RET behaviour (AC flip, id drill-in, button push, no-op user-error)
  done: true
---

## Description

Extend `knot-show-RET' so pressing RET on a frontmatter value (e.g. the "bug" in `type: bug') opens the matching `knot-update-set-*` prompt. Magit-style RET-on-thing-at-point — same metaphor already used for AC flip and id drill-in, just widened to the frontmatter rows.

Lower friction than the `,' transient for the common single-field flip (RET = 1 keystroke vs `,t' = 3). Additive: every existing binding keeps working.

## Design

- `knot-show--render' adds a `'knot-field <symbol>' text property to each editable value's character span. Symbols: `status', `type', `priority', `mode', `assignee', `tags', `parent'.
- `knot-show-RET' grows a new cond branch that reads `(get-text-property (point) 'knot-field)' and dispatches to the right `knot-update-set-*' command.
- Precedence (existing rules stay; new rule slots in at the end):
  1. knot-id button at point → drill-in (unchanged)
  2. AC line at point → flip (unchanged)
  3. Other button at point → push-button (unchanged)
  4. `knot-field' property at point → matching update prompt (NEW)
  5. Otherwise → user-error 'nothing actionable at point' (unchanged)
- Tag list is a single editable span covering the whole `**tags:** …' run; RET anywhere on it opens `knot-update-set-tags' (which already edits the list as a whole).
- Parent line: if the value is rendered as an id button, the button wins (drill-in). To edit parent, use `,P' (existing path).
- Deps / links / blockers / blocking / children / linked sections get no `knot-field' property — those are relationship lists, edited via `D' / `L' transients or `k' (remove at point), not via single-field replace.
- `id', `created', `updated' are read-only; no property.

## Out of scope

- New keybindings (RET is reused; no new keys).
- Editing relationship sections via RET (already covered by other UI).
- Description / design / body edits via RET (covered by `e' / `d' / `b' capture buffers).

## Verification

- Manual: open a show buffer, point on each editable value, press RET, confirm the right prompt fires and the buffer refreshes on commit.
- Regression: AC flip, id drill-in, and `q'/`g'/`,' still work as before.
- byte-compile clean; existing `knot-show-RET' user-error message still fires for un-actionable lines.
