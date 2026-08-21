---
id: kno-01m0jbrvkdq2
title: 'Conditional claim: --if-unassigned on start/update, --assignee "" filters unassigned'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:03.935591783Z'
updated: '2026-08-21T15:34:20.701140865Z'
closed: '2026-08-21T15:34:20.701140865Z'
parent: kno-01m0jbrv6wbe
tags:
- concurrency
- update
acceptance:
- title: knot start <id> --assignee me --if-unassigned succeeds on an unassigned ticket and exits 1 with code already_assigned (naming the current assignee) on an assigned one, without writing
  done: true
- title: update honors --if-unassigned with the same semantics
  done: true
- title: knot ready --assignee "" lists exactly the ready tickets with no assignee
  done: true
- title: Help for start/update/listings and the skill describe the predicate and the empty-assignee filter
  done: true
links:
- kno-01kqgqaxzx98
---

## Description

More than one loop polling the frontier needs 'ready and not taken' as a single answer, and a claim that fails when someone got there first. Today update --status in_progress --assignee x is one write but a second identical write from another agent also exits 0 and overwrites; start on an active ticket exits 0; ready --assignee "" matches nothing.

Changes: (1) knot start gains --assignee. (2) --if-unassigned on start and update: checked before any write; if the ticket already has an assignee, no write, exit 1, JSON envelope {ok:false, error:{code:"already_assigned", current_assignee:"…"}}. (3) On list/ready/blocked/closed/prime, --assignee "" means unassigned, mirroring the write-side clear convention. No claim command, no file locking: the read-modify-write race window is accepted on a single host; stronger guarantees are the linked OCC ticket's scope (stale_write is a different predicate and keeps its own code).

## Notes

**2026-08-21T15:34:20.701140865Z**

start gains --assignee (-a); --if-unassigned on start and update runs one shared predicate in cli.clj before any gate and before any save, so a lost claim writes nothing, exits 1, and emits {ok:false, error:{code:"already_assigned", current_assignee}}. A same-handle re-claim also loses — any non-blank assignee is taken — documented in start's :notes and lifecycle-gates.md. On the read side --assignee "" means unassigned across list/ready/blocked/closed/prime, matching an absent key or a blank value. The status-cmd/update-cmd gate duplication was left alone as out of scope. The read-modify-write window stays open by design; OCC remains kno-01kqgqaxzx98's scope. git:527526f
