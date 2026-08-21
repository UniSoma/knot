---
id: kno-01m0jbrvkdq2
title: 'Conditional claim: --if-unassigned on start/update, --assignee "" filters unassigned'
status: open
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:03.935591783Z'
updated: '2026-08-21T14:32:03.935591783Z'
parent: kno-01m0jbrv6wbe
tags:
- concurrency
- update
acceptance:
- title: knot start <id> --assignee me --if-unassigned succeeds on an unassigned ticket and exits 1 with code already_assigned (naming the current assignee) on an assigned one, without writing
  done: false
- title: update honors --if-unassigned with the same semantics
  done: false
- title: knot ready --assignee "" lists exactly the ready tickets with no assignee
  done: false
- title: Help for start/update/listings and the skill describe the predicate and the empty-assignee filter
  done: false
links:
- kno-01kqgqaxzx98
---

## Description

More than one loop polling the frontier needs 'ready and not taken' as a single answer, and a claim that fails when someone got there first. Today update --status in_progress --assignee x is one write but a second identical write from another agent also exits 0 and overwrites; start on an active ticket exits 0; ready --assignee "" matches nothing.

Changes: (1) knot start gains --assignee. (2) --if-unassigned on start and update: checked before any write; if the ticket already has an assignee, no write, exit 1, JSON envelope {ok:false, error:{code:"already_assigned", current_assignee:"…"}}. (3) On list/ready/blocked/closed/prime, --assignee "" means unassigned, mirroring the write-side clear convention. No claim command, no file locking: the read-modify-write race window is accepted on a single host; stronger guarantees are the linked OCC ticket's scope (stale_write is a different predicate and keeps its own code).
