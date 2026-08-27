---
id: kno-01m10g7vtrnf
title: Fold the small cli pass-throughs left by the list-delta and claim work
status: closed
type: chore
priority: 3
mode: afk
created: '2026-08-27T02:19:32.056234201Z'
updated: '2026-08-27T16:41:40.883755312Z'
closed: '2026-08-27T16:41:40.883755312Z'
tags:
- depth-review
acceptance:
- title: apply-tag-deltas is gone; update-frontmatter calls the list-delta helper directly for tags
  done: true
- title: close/status --assignee sets or clears the assignee through the same helper update --assignee uses; the standalone helper is deleted
  done: true
- title: bb test and clj-kondo clean
  done: true
---

## Description

Two helpers in cli fail the deletion test after the last set of changes: removing them makes no complexity reappear.

apply-tag-deltas is now a one-line forward to the shared list-delta helper; inline the call where update projects opts onto frontmatter.

close --assignee sets-or-clears through a helper of its own, restating the convention update --assignee already applies through clear-when. Reuse the one that exists.

No behaviour change; the existing cli and integration tests pin both paths.

## Notes

**2026-08-27T16:41:40.883755312Z**

Deleted apply-tag-deltas (update-frontmatter calls apply-list-deltas directly for tags) and put-assignee (clear-when hoisted to a top-level helper shared by status-cmd and update-frontmatter). bb test 487/0 failures, clj-kondo clean.
