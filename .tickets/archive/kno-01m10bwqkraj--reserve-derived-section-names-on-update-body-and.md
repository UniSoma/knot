---
id: kno-01m10bwqkraj
title: Reserve derived section names on update --body and flag them in check
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-27T01:03:32.960503583Z'
updated: '2026-08-27T01:53:06.946651269Z'
closed: '2026-08-27T01:53:06.946651269Z'
tags:
- body
- sections
- write-guard
- check
- body-write-surface
acceptance:
- title: update --body with any of the five reserved H2 names exits 1 before any write; each message names the owning field and its write flag
  done: true
- title: knot check emits warning :reserved_section for Blockers, Blocking, Children, or Linked as an H2 in any body, live or archived; :legacy_acceptance_section is unchanged
  done: true
- title: The --body :desc carries the if-a-field-holds-it rule; check --help lists the new code wherever codes are enumerated
  done: true
- title: Tests pin the refusal (text and --json) and the check warning
  done: true
deps:
- kno-01m10bvypjbq
links:
- kno-01m10bvypjbq
- kno-01m10bwqpmqx
- kno-01m10bwqsrk9
---

## Description

`knot show` synthesizes five sections from frontmatter and the graph — `## Acceptance Criteria`, `## Blockers`, `## Blocking`, `## Children`, `## Linked` — and appends them after the stored body. Agents reading that render write the same headings back into the body, where they drift from the field they duplicate (this repo carries a hand-written Blocked-by that contradicts the ticket's live deps). `update --body` help already says the AC section is display-only, and `check` warns on a legacy AC section, but the four graph names have no guard anywhere and `--body` accepts all five.

Reserve the five names at the write surface: `update --body` refuses a body containing any of them as an H2 and exits 1 before any write, and each message names the field that owns it and the flag that writes it (`--acceptance` / `--add-ac`, `knot dep`, `--parent`, `knot link`; Blocking has no writer — it is the inverse of other tickets' deps). The `--description` / `--design` / `add-note` doors are already closed by the H2 guard. `knot edit` cannot be guarded, so `knot check` gains a warning code `:reserved_section` for any of the four graph names found in a body, with a message saying to delete the section because `show` renders it from the field. `:legacy_acceptance_section` stays as it is — it has an automatic fix (`migrate-ac`) and the graph sections do not, since the hand-written prose is usually narrative.

The `--body` `:desc` gains the rule in one line: if a frontmatter field holds it, the body doesn't. Near-synonyms (`## Blocked by`, `## Parent document`, `## Depends on`) are deliberately not detected — that is skill judgment, carried by the sibling show-provenance ticket.

## Notes

**2026-08-27T01:53:06.946651269Z**

ticket/reserved-section-names holds the five names beside body-heading-pat; cli/validate-no-reserved-sections! refuses them on update --body from the pre-write validation block, before resolve-ctx and far before the single store/save!, each message naming the owning field and its writer (--acceptance/--add-ac, knot dep, --parent, knot link; Blocking has none — it is the inverse of other tickets' deps). check/check-reserved-sections warns :reserved_section over live and archived bodies for the four graph names only; Acceptance Criteria is deliberately absent from check/reserved-section-fields so :legacy_acceptance_section keeps its migrate-ac fix. The --body :desc names the five and ends 'if a frontmatter field holds it, the body doesn't'; the code joined check --help :notes and the json-protocol catalogue. Follow-up kno-01m10epnepqs covers the rendered '## Children (2/3)' form, which the bare-name match does not catch.
