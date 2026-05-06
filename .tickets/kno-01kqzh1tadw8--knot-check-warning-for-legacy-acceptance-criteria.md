---
id: kno-01kqzh1tadw8
title: knot check warning for legacy '## Acceptance Criteria' body section
status: open
type: feature
priority: 3
mode: afk
created: '2026-05-06T20:53:11.623411501Z'
updated: '2026-05-06T20:53:11.623411501Z'
tags:
- v0.4
- check
- acceptance
- migration
acceptance:
- title: knot check emits a 'legacy_acceptance_section' warning when a ticket body contains '## Acceptance Criteria'
  done: false
- title: Code is filterable via --code and --severity
  done: false
- title: After migrate-ac runs, the warning disappears for migrated tickets (idempotent end-state)
  done: false
- title: Test covers detection, filtering, and post-migration silence
  done: false
links:
- kno-01kqgqfwk4h1
---

## Description

Add a discoverable nudge for v0.2 → v0.3 upgraders who haven't run `knot migrate-ac` yet. `knot check` already validates structured AC entries (`acceptance_invalid`) but does not detect the *legacy* shape — a `## Acceptance Criteria` body section that would be lifted by `migrate-ac`. Today the only path to discovering the migration is reading release notes.

The cost of missing it: a v0.3 user with legacy bodies sees no errors from `knot check` and no rendered AC checklist on `knot show` (because the structured frontmatter list is empty). Silent half-broken state until they read CHANGELOG.

## Design

New per-ticket warning (not error) emitted by `knot check`:

- **Code:** `legacy_acceptance_section`
- **Severity:** `warning` (not `error` — the ticket still parses fine; this is a migration nudge, not data corruption)
- **Detection:** body contains a `## Acceptance Criteria` heading section (use the existing `knot.acceptance/find-section` predicate)
- **Message:** "Legacy '## Acceptance Criteria' body section found. Run `knot migrate-ac` to lift entries into structured frontmatter."
- **Filterable** by `--code legacy_acceptance_section` and `--severity warning` (per existing `knot check` flag set)

Once every project has migrated, no ticket triggers the warning. Self-cleaning surface area.

Adjacent: keep `migrate-ac` itself hidden (`:hidden? true`) — discoverability comes from `check`, not from `--help` cruft.
