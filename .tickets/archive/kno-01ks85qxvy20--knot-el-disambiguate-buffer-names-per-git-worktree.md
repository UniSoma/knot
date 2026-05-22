---
id: kno-01ks85qxvy20
title: 'knot.el: disambiguate buffer names per git worktree'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-22T15:44:24.958564090Z'
updated: '2026-05-22T16:17:20.535434121Z'
closed: '2026-05-22T16:16:44.855297626Z'
tags:
- emacs
acceptance:
- title: Two worktrees of a knot project yield two distinct `*knot-list*` buffers, each carrying its own filter state and assignable to separate doom workspaces.
  done: true
- title: 'A single-worktree checkout shows the same `*knot-list: <project>*` buffer name as today (no regression).'
  done: true
- title: Opening the same ticket id via `knot-show` and `knot-deps` from two worktrees yields two distinct buffers, each pinned to its worktree's `default-directory`.
  done: true
- title: Capture buffer drafts in one worktree are not destroyed by opening the same id's capture from a different worktree.
  done: true
- title: '`bb test` and `clj-kondo --lint src test` still pass.'
  done: true
---

## Description

Working with multiple git worktrees of the same project under doom emacs workspaces collapses every `*knot-list*`, `*knot-show*`, `*knot-deps*`, `*knot-info*`, and `*knot-capture*` buffer onto a single project-keyed name (from `.knot.edn`). Workspaces cannot isolate them, and per-worktree filter state is lost.

Capture buffers are silently destructive today: opening the same id's capture from a second worktree erases the first worktree's in-progress draft via `erase-buffer` on open.

## Design

- **Worktree-keyed**, not branch-keyed: buffer is bound to the on-disk checkout location, not a label that can flip under it.
- **Conditional suffix**: appears only when the worktree directory basename differs from the project name (case-insensitive). Single-worktree users see no change.
- **Format**: brackets after the project name. Examples: `*knot-list: Knot [knot-feature-x]*`, `*knot-show: Knot [knot-feature-x] · kno-01abc*`.
- **No defcustom** — ship the helper hardcoded; revisit if a real configuration need shows up.

Implementation:

- One new private helper next to `knot-info--project-name` (emacs/knot.el:468): `knot-info--worktree-suffix` — takes optional info, returns either `nil` or a leading-space string like ` [knot-feature-x]`. Owns the format completely.
- Five call-site edits to compose project + suffix: `knot-list--buffer-name`, `knot-show--buffer-name`, `knot-deps--buffer-name`, `knot-info-show`, `knot-capture--buffer-name`. Skip `knot-delete--refusal-buffer-name` (id-only, throwaway).
- One-line docstring on the helper carrying the branch-vs-worktree rationale.

Out of scope: ADR, `CONTEXT.md` update, renaming live buffers across worktree changes, branch-keyed mode behind a toggle.

## Notes

**2026-05-22T16:16:44.855297626Z**

Shipped: knot.el buffer names gain a  suffix when the worktree directory basename differs from the project name (case-insensitive), so multiple git worktrees of the same project yield distinct `*knot-list*`, `*knot-show*`, `*knot-deps*`, `*knot-info*`, and `*knot-edit-*` buffers — each with its own buffer-local filters, default-directory, and capture draft. Single-worktree checkouts see the names unchanged. Implemented as a new private helper `knot-info--worktree-suffix` (worktree-keyed, not branch-keyed) threaded through the four `--buffer-name` helpers + `knot-info-show`; `knot-delete--refusal-buffer-name` left as-is (id-only, throwaway). bb lint:elisp / bb test / clj-kondo all green.

**2026-05-22T16:17:20.535434121Z**

Summary correction (prior note got mangled by shell redirection on `<worktree>`): the suffix that buffer names gain is `" [<basename>]"` — e.g. `*knot-list: Knot [knot-feature-x]*`, `*knot-show: Knot [knot-feature-x] · kno-01abc*`.
