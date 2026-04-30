---
id: kno-01kqe7aka8q9
title: 'prime: post-review cleanups (naming, doc note, shared constant)'
status: open
type: chore
priority: 3
mode: afk
created: '2026-04-30T03:36:08.264863311Z'
updated: '2026-04-30T03:36:08.264863311Z'
tags:
- p3
external_refs:
- 0ffb999
- 30e31c6
---

## Description

Cleanup items deferred from the post-commit code review of 30e31c6
(critical+important fixes shipped in 0ffb999; these minors deferred):

1. Stale-flag asymmetry between in_progress and ready JSON arrays.
   Same in_progress ticket appears in both arrays in 'prime --json'
   (pre-existing query/ready behavior — non-blocked + non-terminal
   includes in_progress). The :prime-stale? decoration only flows
   through cli/in-progress-tickets, so the ready copy of that ticket
   has no \"stale\":true field.
   - Recommended fix: doc note in README and SKILL.md ("stale appears
     only on in_progress entries; iterate .in_progress to find
     stalled work"), NOT decorate both arrays.
   - Rationale: keeps the JSON surface narrow; decorating both adds
     code complexity for marginal value.

2. Rename cli/in-progress-tickets → cli/prime-in-progress-tickets.
   The fn now decorates with :prime-stale? — the bare "select" name
   is misleading. Either rename or add a brief docstring noting the
   returned maps are augmented. Pure stylistic.

3. Extract shared skill-pointer line across preambles.
   output/prime-preamble-found and output/prime-preamble-afk both end
   with "For the full reference (...) invoke the \`knot\` skill."
   Extract that sentence to a private constant so the two preambles
   can't drift if one ever gets reworded.

Bundle as one small PR or skip if low value. None blocks anything;
all surface from a clean code review.
