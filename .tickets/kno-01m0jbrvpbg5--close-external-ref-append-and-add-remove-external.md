---
id: kno-01m0jbrvpbg5
title: close --external-ref (append) and --add/--remove-external-ref on update
status: open
type: feature
priority: 3
mode: afk
created: '2026-08-21T14:32:04.043597337Z'
updated: '2026-08-21T14:32:04.043597337Z'
parent: kno-01m0jbrv6wbe
tags:
- close
- update
acceptance:
- title: knot close <id> --summary … --external-ref git:<sha> records the ref alongside existing refs and the close lands in one write
  done: false
- title: update --add-external-ref / --remove-external-ref are idempotent and refuse to combine with --external-ref
  done: false
- title: Help for close/update, the skill (git:<sha> convention) and CHANGELOG move in the same commit
  done: false
---

## Description

The per-unit commit → close pairing is the orchestrator's traceability. Today the sha goes into --summary prose: close has no --external-ref, and update --external-ref is replace-all, so recording a commit clobbers existing refs and takes two calls.

Changes: close gains --external-ref (repeatable, append, idempotent). update gains --add-external-ref / --remove-external-ref (repeatable, idempotent), mutually exclusive with the replace-all --external-ref, mirroring --tags vs --add-tag. No dedicated commit field and no auto-fill from git HEAD (wrong on every close not paired with a commit). git:<sha> is a convention documented in the skill; closed --json already emits external_refs.
