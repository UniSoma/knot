---
id: kno-01kqsgmey8dm
title: Derive create/reopen intake status from config instead of hardcoded open
status: closed
type: bug
priority: 3
mode: afk
created: '2026-05-04T12:50:27.400732634Z'
updated: '2026-05-05T10:31:24.054907102Z'
closed: '2026-05-05T10:31:24.054907102Z'
parent: kno-01kqgqapwqvh
tags:
- v0.3
- audit
- cleanup
---

## Description

Audit finding from kno-01kqgqapwqvh.

Runtime sites:
- src/knot/cli.clj:119 sets new tickets to \"open\" inside create-cmd.
- src/knot/cli.clj:263 sends reopen-cmd to \"open\".

That couples lifecycle behavior to the canonical status literal instead of the project's configured intake lane. For projects that customize :statuses / :active-status away from the v0 defaults, both `knot create` and `knot reopen` should target the first status that is neither active nor terminal (or an explicitly defined replacement if we add one), not the raw string \"open\".

Acceptance:
- Create derives its initial non-terminal, non-active status from config.
- Reopen restores that same config-derived intake status.
- Coverage includes a custom-status project (for example [\"todo\" \"active\" \"done\"]).

## Notes

**2026-05-05T10:31:24.054907102Z**

Add private first-intake-status helper and wire create-cmd + reopen-cmd to derive their target from :statuses / :active-status / :terminal-statuses (fallback "open" only when no intake exists). New cli-test/create-and-reopen-intake-status-from-config-test pins behavior under default and custom :statuses ("todo" "active" "done").
