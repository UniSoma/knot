---
id: kno-01m10g7a1ffd
title: 'Every listing path goes through the view: prime''s recently-closed and the four command wrappers'
status: open
type: chore
priority: 3
mode: afk
created: '2026-08-27T02:19:13.839016246Z'
updated: '2026-08-27T02:19:13.839016246Z'
tags:
- depth-review
acceptance:
- title: knot prime's recently_closed section is produced by listing/rows with the :closed source; closed? and by-closed-desc are private to listing
  done: false
- title: The list/ready/blocked/closed command wrappers are gone; main's list handler passes only the command key to one public view entry point
  done: false
- title: Primer output and every listing command's text and --json output are byte-identical to before (integration and json-contract tests pin them)
  done: false
- title: bb test and clj-kondo clean
  done: false
---

## Description

listing/rows is the view: source -> closure -> component -> filters -> sort -> limit -> columns. Two callers still re-derive pieces of it.

prime's recently_closed section does its own filter-closed -> sort-by-closed -> take, which is exactly the :closed source with a limit. listing's closed? and by-closed-desc are public only to serve it; route prime through the view and make both private.

list, ready, blocked and closed each exist as a one-line wrapper forwarding a source keyword to the private view runner, while main's shared list handler is handed both the command key and the wrapper — the same choice named twice. Make the view runner the entry point, have the handler pass the key alone, and delete the four wrappers; the cli tests that called them call the view directly.

Primer and every listing command's output stay unchanged.
