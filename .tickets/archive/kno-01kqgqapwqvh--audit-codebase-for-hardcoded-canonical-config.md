---
id: kno-01kqgqapwqvh
title: Audit codebase for hardcoded canonical-config literals
status: closed
type: chore
priority: 3
mode: afk
created: '2026-05-01T02:54:18.007748541Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-05-04T12:51:18.830399477Z'
tags:
- v0.3
- audit
- cleanup
- needs-triage
links:
- kno-01kqdaxz86nv
acceptance:
- title: Codebase grep run for each value class listed in the description
  done: true
- title: Findings recorded in this ticket's notes (file:line + value + remediation note)
  done: true
- title: Child ticket filed for each genuinely-hardcoded site not already covered
  done: true
- title: 'Optional: regression test or lint rule that fails CI if a `:statuses`/`:modes`/`:types` literal appears in non-data namespaces'
  done: false
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

## Notes

**2026-05-01T03:11:30.083399247Z**

Correction to description: kno-01kqg37mssy3 (status color map hardcode) is closed — shipped commit fef69de ('derive ls-table status colors from config roles, not literals'). The audit scope is unchanged; treat that ticket as the precedent for the role-derivation approach when remediating any additional sites surfaced by the grep.

**2026-05-04T12:50:47.558730190Z**

Audit run on production namespaces (`src/`) with targeted greps for each value class from the ticket:
- statuses / terminal statuses: `rg -n '"(open|in_progress|closed)"' src`
- modes: `rg -n '"(afk|hitl)"' src`
- types: `rg -n '"(bug|feature|task|epic|chore)"' src`
- priority literals / non-validation sites: `rg -n '\bpriority\b|\b0\b|\b1\b|\b2\b|\b3\b|\b4\b' src/knot/{cli,output,query,check,help,config}.clj`

Findings (new child tickets filed where warranted):
- `src/knot/cli.clj:119` — create-cmd sets `:status "open"` directly. Together with reopen below, filed as child `kno-01kqsgmey8dm`.
- `src/knot/cli.clj:263` — reopen-cmd transitions to `"open"` directly. Covered by child `kno-01kqsgmey8dm`.
- `src/knot/output.clj:421-423` — ls-table fallback status context bakes canonical status literals (`["open" "in_progress" "closed"]`, `#{"closed"}`, `"in_progress"`). Filed as child `kno-01kqsgmeycvx`.
- `src/knot/output.clj:664` — prime-text selects the AFK preamble by testing `mode-norm == "afk"`. Filed as child `kno-01kqsgmey9ew`.

Covered / triaged but no new child ticket:
- `src/knot/output.clj:326` — priority `0` gets special coloring (`[:red :bold]`); this is the only additional non-validation priority literal I found in production code, and it is already within scope of `kno-01kqdaxz86nv` (richer per-value table colors).
- `src/knot/cli.clj:255` — close-cmd fallback `"closed"` is fallback-only; normal behavior already derives the terminal target from `:statuses` + `:terminal-statuses`, so I did not file a separate ticket.
- `src/knot/main.clj:773` — `"closed"` is the CLI subcommand name, not a config-driven ticket-status literal.
- `src/knot/config.clj` type/status/mode literals are the canonical defaults themselves, so they are not audit findings.
- No additional production-code type literals outside `config/defaults` surfaced.

**2026-05-04T12:51:18.830399477Z**

Audited src for status/mode/type/priority literals, recorded findings in notes, and filed follow-up children for create/reopen intake status, prime AFK preamble selection, and ls-table fallback status context; noted the priority-0 color site is already covered by kno-01kqdaxz86nv.
