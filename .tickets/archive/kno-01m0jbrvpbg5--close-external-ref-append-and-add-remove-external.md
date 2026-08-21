---
id: kno-01m0jbrvpbg5
title: close --external-ref (append) and --add/--remove-external-ref on update
status: closed
type: feature
priority: 3
mode: afk
created: '2026-08-21T14:32:04.043597337Z'
updated: '2026-08-21T16:15:28.112154870Z'
closed: '2026-08-21T16:15:28.112154870Z'
parent: kno-01m0jbrv6wbe
tags:
- close
- update
acceptance:
- title: knot close <id> --summary … --external-ref git:<sha> records the ref alongside existing refs and the close lands in one write
  done: true
- title: update --add-external-ref / --remove-external-ref are idempotent and refuse to combine with --external-ref
  done: true
- title: Help for close/update, the skill (git:<sha> convention) and CHANGELOG move in the same commit
  done: true
external_refs:
- git:eb1e170
---

## Description

The per-unit commit → close pairing is the orchestrator's traceability. Today the sha goes into --summary prose: close has no --external-ref, and update --external-ref is replace-all, so recording a commit clobbers existing refs and takes two calls.

Changes: close gains --external-ref (repeatable, append, idempotent). update gains --add-external-ref / --remove-external-ref (repeatable, idempotent), mutually exclusive with the replace-all --external-ref, mirroring --tags vs --add-tag. No dedicated commit field and no auto-fill from git HEAD (wrong on every close not paired with a commit). git:<sha> is a convention documented in the skill; closed --json already emits external_refs.

## Notes

**2026-08-21T16:15:28.112154870Z**

close gains --external-ref (repeatable, appending, idempotent), applied inside the same cond-> as the status change and the summary note so the close and the ref are one write. update gains --add-external-ref / --remove-external-ref mirroring --add-tag/--remove-tag down to the validator, refused in combination with the replace-all --external-ref. git:<sha> is documented convention only: nothing is parsed, verified, or auto-filled from HEAD. Two boundaries recorded rather than fixed: --external-ref is declared on close alone, so the generic terminal-status path uses update --status closed --add-external-ref; and a blank ref no-ops on close but throws invalid_argument on update, because transition-handler builds its opts outside the try where a throw would escape uncaught. This close recorded its own commit through the new flag.
