---
id: kno-01kqgqd3vzx6
title: Decide whether prime in-progress nudge consolidates under knot check
status: closed
type: task
priority: 4
mode: hitl
created: '2026-05-01T02:55:36.831466844Z'
updated: '2026-05-05T12:21:44.988431727Z'
closed: '2026-05-05T12:21:44.988431727Z'
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
  done: true
- title: 'If consolidating: `stale_in_progress` warning code added to `knot check`; `prime-in-progress-nudge` removed; tests cover the new path'
  done: false
- title: 'If keeping separate: ticket closed with note; no code changes'
  done: true
- title: 'If both: justification documented for why two surfaces is the right call here'
  done: false
---

## Description

Two distinct things in the prime in-progress block could conceivably move under `knot check`. The original framing conflated them; this is the corrected version.

1. **`prime-in-progress-nudge`** (`output.clj:572`) — a static section-header instruction line ("Resume here if the user picks up mid-stream."). Pure steering text. Not staleness, not validation. Not a candidate for migration.
2. **`stale-in-progress?` / `:prime-stale?` / `[stale]` prefix** (`cli.clj:937-992`, `output.clj:608, 724`) — a 14-day-old-`:updated` per-ticket flag that prefixes `[stale]` in text and adds `"stale":true` in JSON. This *is* the candidate for a `stale_in_progress` warning under `knot check`.

So the decision is narrower than the original framing: should the per-ticket staleness flag move from `prime` into `knot check` as a `warning`-severity issue?

Tradeoffs:

- `check` is for **invariants** (cycles, dangling refs, schema). A 30-day-old in-progress ticket isn't invalid — it's a workflow signal. Calling it a `warning` stretches `check`'s semantics from "validator" to "general health linter."
- `prime` is a **curated AI-context view**, not a list. The `[stale]` prefix is editorial — "look here first" — and the zero-extra-call cost is the whole point of prime. Forcing agents to run a second command to recover that signal regresses the AFK-bootstrap ergonomics.
- The two surfaces look symmetric (both flag tickets) but are semantically distinct (steer vs. validate).
- Duplication (option 3) is the worst-of-both-worlds shape via-negativa.

**Decision: Option 2 — keep separate.** `prime` keeps `:prime-stale?` and the `[stale]` prefix; `knot check` does not gain `stale_in_progress`. Reconsider only if `check`'s scope is ever repositioned from validator → health-summary, which is a bigger product call than this ticket.

## Notes

**2026-05-05T12:21:44.988431727Z**

Decision: Option 2 — keep separate. The original framing conflated two things: (1) prime-in-progress-nudge in output.clj:572, a static section-header instruction string ('Resume here if the user picks up mid-stream.'), which was never a real migration candidate; (2) the per-ticket staleness flag (stale-in-progress? / :prime-stale? / [stale] prefix in cli.clj:937-992 + output.clj:608,724), which is the actual signal that overlaps with a hypothetical knot check stale_in_progress warning. Narrowed the decision to (2). Chose to leave it in prime because check is an invariant validator (cycles, dangling refs, schema mismatches) and a 14-day-old in-progress ticket isn't invalid — it's an editorial/workflow signal. Migrating it would stretch check's semantics from 'validator' to 'general health linter' and regress prime's zero-extra-call AFK-bootstrap ergonomics by forcing a second command to recover the [stale] hint. Option 3 (both) is the duplication anti-pattern the ticket itself flagged. Reconsider only if check's scope is ever repositioned from validator → health-summary, which is a bigger product call than this ticket. No code changes; description rewritten to separate the nudge string from the staleness flag so the rationale is recoverable from the archive.
