---
id: kno-01kqgqafcxvv
title: Verify reopen and close round-trip atomicity
status: open
type: bug
priority: 2
mode: afk
created: '2026-05-01T02:54:10.333902682Z'
updated: '2026-05-01T02:54:10.333902682Z'
tags:
- v0.3
- store
- needs-triage
---

## Description

Verify that the file moves between `.tickets/` and `.tickets/archive/` performed by `knot close` (auto-archive on terminal transition) and `knot reopen` (move-back from archive) are atomic.

Investigate the current implementation in `store.clj` (or wherever the file-move happens) and determine whether a crash mid-operation can leave the system in a half-moved state — e.g. frontmatter status updated but file not yet moved, or file copied but not removed from the source path. Both directions need checking.

If the operation is non-atomic, fix it. Preferred shape: write the new file content to the destination via an OS-level rename (single filesystem operation, atomic on POSIX), and let the source path's removal piggyback on rename semantics where possible.

Q7 of the v0.3 API review named this a non-negotiable invariant.

## Acceptance Criteria

- [ ] Audit current `close` (auto-archive) and `reopen` (move-back) paths for atomicity
- [ ] Findings recorded in this ticket's notes
- [ ] If non-atomic: fix using OS-level rename (single fs operation) where possible
- [ ] Test: simulated crash mid-operation does not leave the system in inconsistent state (file in two places, or in neither)
- [ ] Behavior on Windows considered (file-locking semantics differ — coordinate with kno-01kqcvp72htb)
