---
id: kno-01kqe7aka8q9
title: 'prime: post-review cleanups (naming, doc note, shared constant)'
status: closed
type: chore
priority: 3
mode: afk
created: '2026-04-30T03:36:08.264863311Z'
updated: '2026-05-06T01:49:33.267126098Z'
closed: '2026-05-06T01:49:33.267126098Z'
tags:
- p3
external_refs:
- 0ffb999
- 30e31c6
links:
- kno-01kqgqd3vzx6
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

## Notes

**2026-05-01T03:11:28.243419727Z**

Items #1 (stale-flag asymmetry doc note) and #2 (rename cli/in-progress-tickets to cli/prime-in-progress-tickets) are conditional on kno-01kqgqd3vzx6 (decide whether prime in-progress nudge consolidates under knot check). If that decision consolidates the staleness logic under 'knot check', prime-in-progress-tickets stops decorating and both items become moot. Item #3 (extract shared skill-pointer constant) survives either way.

**2026-05-06T01:49:33.267126098Z**

Shipped all three post-review cleanups:

1. Stale-flag asymmetry doc note added to README (paragraph after the prime --json shape block), SKILL.md (paragraph after the --json | jq advice), and references/json-protocol.md (extended prime row's Notes column). Wording: 'stale: true' appears only on in_progress entries; iterate .in_progress to find stalled work. Narrow JSON surface preserved on purpose — no code change.

2. Renamed cli/in-progress-tickets -> cli/prime-in-progress-tickets (defn-, single caller in src/knot/cli.clj:1154, no test references). Docstring extended with a clause noting the prime- prefix because the result is decorated specifically for prime's consumption, not a generic in-progress selector.

3. Extracted output/prime-skill-pointer (private defn-) producing the closing sentence 'For the full reference (<topics>), invoke the knot skill.' Both prime-preamble-found and prime-preamble-afk now compose through it via str, with their differing parenthetical content passed as the argument (found: '...AFK vs HITL', afk: omits that phrase since the agent is already in afk mode). A reword of the wrapping template can't drift between the two preambles anymore.

CHANGELOG entry added under [Unreleased]/Fixed.

Verification: bb test 318/4093/0; clj-kondo baseline preserved (4 errors / 5 warnings, all pre-existing); prime and prime --mode afk render identically to pre-change; prime --json envelope unchanged.
