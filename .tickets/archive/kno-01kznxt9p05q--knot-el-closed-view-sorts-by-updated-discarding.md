---
id: kno-01kznxt9p05q
title: 'knot.el: closed view sorts by :updated, discarding the CLI''s :closed descending order'
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-10T13:29:26.975920190Z'
updated: '2026-08-10T13:36:22.190881609Z'
closed: '2026-08-10T13:36:22.190881609Z'
tags:
- emacs
- sort
acceptance:
- title: closed view defaults to sort=closed desc, matching knot closed --json order
  done: true
- title: Age column derives from :closed when the row carries one (row-driven, not view-driven)
  done: true
- title: sort transient offers 'x' closed, shown only in the closed view
  done: true
- title: CONTEXT.md gains a Close time glossary entry; emacs/README.md documents both behaviors
  done: true
---

## Description

knot.el pins the closed view to `updated` descending (knot-list--view-default-sort) and re-sorts CLI rows client-side, discarding the order `knot closed` already guarantees (src/knot/cli.clj by-closed-desc: :closed descending, missing stamp last). 36 of 117 closed tickets in this repo have :closed != :updated, so post-close notes/edits float tickets to the top as if they were closed later than they were.

Fix: add `closed` as a sort key, default the closed view to it, and derive the Age column from a row's :closed stamp when present (row-driven, so --status closed in a live view agrees).

## Notes

**2026-08-10T13:36:22.190881609Z**

knot.el now sorts the closed view by close time descending, reproducing the order knot closed already emits (cli/by-closed-desc) instead of re-sorting by :updated and misplacing the 36-of-117 archived tickets whose :closed and :updated diverge. 'closed' joins the sort key set as the closed view's default, offered in the sort transient under 'x' behind an :if predicate so it appears only there. The Age column ages from a row's :closed stamp when present — row-driven, so --status closed in a live view agrees. Two divergences left deliberate and documented in place: nil :closed stays direction-symmetric rather than always sinking last as the CLI does, and the Age column header still sorts by :updated everywhere. Verified in batch Emacs against live CLI data: rendered order matches knot closed --json exactly; the previous default differed in 36 positions.
