---
id: kno-01kqgqd3vzx6
title: Decide whether prime in-progress nudge consolidates under knot check
status: open
type: task
priority: 4
mode: hitl
created: '2026-05-01T02:55:36.831466844Z'
updated: '2026-05-05T01:38:54.088449090Z'
tags:
- v0.3
- cli
- needs-triage
deps:
- kno-01kqgqc2ks70
links:
- kno-01kqe7aka8q9
acceptance:
- title: Decision recorded with rationale
  done: false
- title: 'If consolidating: `stale_in_progress` warning code added to `knot check`; `prime-in-progress-nudge` removed; tests cover the new path'
  done: false
- title: 'If keeping separate: ticket closed with note; no code changes'
  done: false
- title: 'If both: justification documented for why two surfaces is the right call here'
  done: false
---

## Description

The existing `prime` output emits a "nudge" line for in-progress tickets that have been sitting too long (see `stale-in-progress?` and `prime-in-progress-nudge` in src/knot/cli.clj). With `knot check` (kno-01kqgqc2ks70) shipping in v0.3, there is now a natural home for staleness as a `stale_in_progress` warning code under `check`.

Decide:

1. **Consolidate** — drop the `prime` nudge. Move staleness detection into `knot check` as a `warning`-severity issue. Agents reading `prime` no longer get inline nudges; they run `knot check --severity warning` for that signal.
2. **Keep separate** — `prime` keeps its nudge as part of the curated context view; `check` does not duplicate the signal.
3. **Both** — `check` gains `stale_in_progress` *and* `prime` keeps the nudge. Two signal sources for the same fact.

Tradeoffs:
- `prime` is the canonical AI-context-injection surface; nudges there are zero-extra-call and high-leverage for steering agents.
- `check` is the integrity-validation surface; staleness is a soft warning, arguably orthogonal to integrity.
- Duplication (option 3) is the worst-of-both-worlds shape via-negativa flagged on other questions.

Recommendation likely: option 1 for surface discipline, but `prime`'s zero-call ergonomics is real — needs design judgment.
