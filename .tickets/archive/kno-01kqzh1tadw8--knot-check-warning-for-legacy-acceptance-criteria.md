---
id: kno-01kqzh1tadw8
title: knot check warning for legacy '## Acceptance Criteria' body section
status: closed
type: feature
priority: 3
mode: afk
created: '2026-05-06T20:53:11.623411501Z'
updated: '2026-05-06T23:42:46.758349559Z'
closed: '2026-05-06T23:35:17.982768024Z'
tags:
- v0.4
- check
- acceptance
- migration
acceptance:
- title: knot check emits a 'legacy_acceptance_section' warning when a ticket body contains '## Acceptance Criteria'
  done: true
- title: Code is filterable via --code and --severity
  done: true
- title: After migrate-ac runs, the warning disappears for migrated tickets (idempotent end-state)
  done: true
- title: Test covers detection, filtering, and post-migration silence
  done: true
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

## Notes

**2026-05-06T23:35:17.982768024Z**

knot check now emits :legacy_acceptance_section warning aligned with what migrate-ac fixes; self-clears after migration. Filterable via --code/--severity. Verified end-to-end: warning surfaces, exit stays 0 (warning, not error), JSON envelope shape unchanged, disappears after migrate-ac runs.
