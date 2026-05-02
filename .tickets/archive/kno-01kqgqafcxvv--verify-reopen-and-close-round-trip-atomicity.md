---
id: kno-01kqgqafcxvv
title: Verify reopen and close round-trip atomicity
status: closed
type: bug
priority: 2
mode: afk
created: '2026-05-01T02:54:10.333902682Z'
updated: '2026-05-02T19:45:20.310127164Z'
closed: '2026-05-02T19:45:20.310127164Z'
tags:
- v0.3
- store
- needs-triage
links:
- kno-01kqcvp72htb
---

## Description

Verify that the file moves between `.tickets/` and `.tickets/archive/` performed by `knot close` (auto-archive on terminal transition) and `knot reopen` (move-back from archive) are atomic.

Investigate the current implementation in `store.clj` (or wherever the file-move happens) and determine whether a crash mid-operation can leave the system in a half-moved state — e.g. frontmatter status updated but file not yet moved, or file copied but not removed from the source path. Both directions need checking.

If the operation is non-atomic, fix it. Preferred shape: write the new file content to the destination via an OS-level rename (single filesystem operation, atomic on POSIX), and let the source path's removal piggyback on rename semantics where possible.

Q7 of the v0.3 API review named this a non-negotiable invariant.

## Acceptance Criteria

- [x] Audit current `close` (auto-archive) and `reopen` (move-back) paths for atomicity
- [x] Findings recorded in this ticket's notes
- [x] If non-atomic: fix using OS-level rename (single fs operation) where possible
- [x] Test: simulated crash mid-operation does not leave the system in inconsistent state (file in two places, or in neither)
- [x] Behavior on Windows considered (file-locking semantics differ — coordinate with kno-01kqcvp72htb)

## Notes

**2026-05-02T19:40:52.436404751Z**

**Audit findings**

`save!` (src/knot/store.clj) was non-atomic for cross-directory transitions:

  1. `(spit target ...)` wrote new content at the target path.
  2. `(fs/delete-if-exists source)` removed the prior path.

A crash between (1) and (2) left the file in *two places* (live + archive). Self-heal converged on the next save, but the system was briefly inconsistent — `load-all` returned duplicates and `resolve-id` could surface the wrong copy. The "in neither" state was impossible (target write happened before any deletion).

Both close (live → archive) and reopen (archive → live) flowed through the same `save!`, so both directions were affected.

**Fix**

`save!` now writes via OS-level rename. For cross-directory transitions:

  1. Render new content, write it atomically at the *source* path (temp file in source dir + `Files/move` ATOMIC_MOVE within that dir).
  2. `Files/move(source, target, ATOMIC_MOVE)` — single `rename(2)` syscall. Source removal piggybacks on the rename's atomicity.

For same-path saves, content is replaced via temp-and-rename within the target dir. A trailing sweep handles legacy stragglers (pre-fix duplicates, hand-edits).

At every observable moment the file lives in *exactly one* on-disk location.

**Tests**

`save-close-crash-leaves-file-in-one-place-test` and `save-reopen-crash-leaves-file-in-one-place-test` simulate a crash via `with-redefs` on `fs/delete-if-exists` and assert exactly one file remains. Both fail RED on the legacy implementation (file in 2 places) and pass GREEN on the fix. Full suite: 254 tests, 0 failures.

**Windows note**

`StandardCopyOption/ATOMIC_MOVE` is supported on NTFS via `MoveFileEx` but combined with `REPLACE_EXISTING` the semantics are weaker than on POSIX (the underlying `MOVEFILE_REPLACE_EXISTING` is not strictly atomic across all FS types). Cross-FS atomic moves throw `AtomicMoveNotSupportedException` everywhere. Within a single project's `.tickets/` and `.tickets/archive/` (sibling dirs, same filesystem), this is not a concern in practice. Coordinate further hardening with kno-01kqcvp72htb.

**2026-05-02T19:45:20.310127164Z**

save! now uses OS-level rename for cross-directory transitions: write new content at source via temp+rename, then Files/move source → target with ATOMIC_MOVE so source removal piggybacks on rename(2) semantics. File lives in exactly one location at every observable moment. Tests simulate a crash mid-operation and assert the invariant; full suite 254/254 green.
