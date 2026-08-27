---
id: kno-01m10g73xa5w
title: Column help notes live on the listing column declaration
status: closed
type: chore
priority: 3
mode: afk
created: '2026-08-27T02:19:07.562496158Z'
updated: '2026-08-27T16:34:57.894594937Z'
closed: '2026-08-27T16:34:57.894594937Z'
tags:
- depth-review
acceptance:
- title: Each listing/columns entry carries its help note; help builds the shared listing NOTES by iterating the declarations
  done: true
- title: The help-side column-notes map and the help test that pairs it with listing/columns are deleted
  done: true
- title: knot list --help, ready --help and blocked --help output is byte-identical to before
  done: true
- title: bb test and clj-kondo clean
  done: true
---

## Description

listing/columns declares every computed column once — key, header, alignment, which views attach it, how its cell and JSON render. The help text for those columns lives apart from it: a second map in help keyed by the column's key, with a test that fails when the two drift, and each note re-typing the header the declaration already carries.

Move the note onto the declaration. The shared NOTES block that list, ready and blocked render then maps straight over listing/columns, the help-side key -> note map goes, and the drift test has nothing left to police. knot list --help stays byte-identical.

## Notes

**2026-08-27T16:34:57.894594937Z**

Moved each column's help note onto its listing/columns declaration as :note; help builds the shared NOTES from header + note over the declarations. Deleted help/column-notes and the listing-notes-document-computed-columns-test drift guard. list/ready/blocked --help verified byte-identical via diff; bb test 486 tests / 0 failures, clj-kondo clean.
