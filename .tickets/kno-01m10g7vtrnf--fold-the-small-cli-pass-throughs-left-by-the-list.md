---
id: kno-01m10g7vtrnf
title: Fold the small cli pass-throughs left by the list-delta and claim work
status: open
type: chore
priority: 3
mode: afk
created: '2026-08-27T02:19:32.056234201Z'
updated: '2026-08-27T02:19:32.056234201Z'
tags:
- depth-review
acceptance:
- title: apply-tag-deltas is gone; update-frontmatter calls the list-delta helper directly for tags
  done: false
- title: close/status --assignee sets or clears the assignee through the same helper update --assignee uses; the standalone helper is deleted
  done: false
- title: bb test and clj-kondo clean
  done: false
---

## Description

Two helpers in cli fail the deletion test after the last set of changes: removing them makes no complexity reappear.

apply-tag-deltas is now a one-line forward to the shared list-delta helper; inline the call where update projects opts onto frontmatter.

close --assignee sets-or-clears through a helper of its own, restating the convention update --assignee already applies through clear-when. Reuse the one that exists.

No behaviour change; the existing cli and integration tests pin both paths.
