---
id: kno-01kqgqaxzx98
title: 'Future-work: optimistic concurrency control via :updated'
status: open
type: task
priority: 4
mode: hitl
created: '2026-05-01T02:54:25.277798011Z'
updated: '2026-05-05T01:38:54.088449090Z'
tags:
- future
- concurrency
- needs-triage
acceptance:
- title: Decision recorded once a real conflict is observed (or once parallelism scales)
  done: false
- title: '`--if-updated <ts>` flag added to all mutating commands'
  done: false
- title: 'Failure envelope shape implemented: `{ok: false, error: {code: "stale_write", current_updated: "..."}}`'
  done: false
- title: Tests cover both happy path (matching ts) and stale path (mismatched ts)
  done: false
- title: README "Concurrency" section updated to document the new opt-in path
  done: false
---

## Description

Placeholder ticket deferred from v0.3 per Q12 of the API review.

Today knot does no locking and assumes one writer per ticket at a time. As multi-agent workflows scale, two agents writing the same ticket simultaneously will produce git conflicts. The mitigation is optimistic concurrency control via the `:updated` timestamp:

- Mutating commands accept `--if-updated <ts>`.
- The write fails with `{ok: false, error: {code: "stale_write", current_updated: "..."}}` if the ticket has been touched since the agent's last read.
- The JSON envelope adopted in the v0.3 cut already returns `updated` on every read, so the round-trip is naturally available.

This is **not** for v0.3. Reopen this ticket once real conflicts surface in practice or once multi-agent parallelism becomes routine. Until then, the concurrency model is documented in README (per the v0.3 docs slice).
