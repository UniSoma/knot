---
id: kno-01kqgqapwqvh
title: Audit codebase for hardcoded canonical-config literals
status: open
type: chore
priority: 3
mode: afk
created: '2026-05-01T02:54:18.007748541Z'
updated: '2026-05-01T03:11:30.083399247Z'
tags:
- v0.3
- audit
- cleanup
- needs-triage
links:
- kno-01kqdaxz86nv
---

## Description

Audit the codebase for any place where canonical config-driven values appear as string literals in code rather than being read from `.knot.edn`.

Values to grep:
- `:statuses` values: `"in_progress"`, `"open"`, `"closed"`, etc.
- `:terminal-statuses` values
- `:modes` values: `"afk"`, `"hitl"`
- `:types` values: `"bug"`, `"feature"`, `"task"`, `"epic"`, `"chore"`
- Priority literals as numbers in non-validation contexts

Three known sites of this pattern have already surfaced:
- kno-01kqdat9xssc — `knot start` hardcoded `"in_progress"` (fixed via `:active-status`)
- kno-01kqg37mssy3 — status color map hardcodes `"in_progress"`/`"open"`/`"closed"` (open)
- `--afk`/`--hitl` shortcut flags hardcode mode names (covered by separate v0.3 ticket)

The goal of this ticket is to find any *additional* sites and file child tickets per finding. Bonus: write a regression discipline (test, lint rule, or `knot check` codebase rule) that catches future re-introduction.

## Acceptance Criteria

- [ ] Codebase grep run for each value class listed in the description
- [ ] Findings recorded in this ticket's notes (file:line + value + remediation note)
- [ ] Child ticket filed for each genuinely-hardcoded site not already covered
- [ ] Optional: regression test or lint rule that fails CI if a `:statuses`/`:modes`/`:types` literal appears in non-data namespaces

## Notes

**2026-05-01T03:11:30.083399247Z**

Correction to description: kno-01kqg37mssy3 (status color map hardcode) is closed — shipped commit fef69de ('derive ls-table status colors from config roles, not literals'). The audit scope is unchanged; treat that ticket as the precedent for the role-derivation approach when remediating any additional sites surfaced by the grep.
