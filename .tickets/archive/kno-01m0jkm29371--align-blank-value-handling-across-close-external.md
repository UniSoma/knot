---
id: kno-01m0jkm29371
title: Align blank-value handling across close --external-ref and update --add-external-ref
status: closed
type: bug
priority: 3
mode: afk
created: '2026-08-21T16:49:15.554861067Z'
updated: '2026-08-27T16:50:46.230596747Z'
closed: '2026-08-27T16:50:46.230596747Z'
tags:
- close
- update
- consistency
acceptance:
- title: 'close --external-ref "" and update --add-external-ref "" agree: both exit 1 with `--<flag> value must not be blank`, invalid_argument under --json, and neither writes — the ticket is still open with no summary'
  done: true
- title: close --external-ref "  git:abc " records git:abc; close --external-ref "   " is rejected as blank
  done: true
- title: update --external-ref "" still clears external_refs to []
  done: true
- title: start, status, reopen and every other close flag keep their existing exit codes and envelopes
  done: true
- title: knot close --help no longer says a blank value is ignored; the --external-ref flag text on close says a blank is rejected
  done: true
external_refs:
- git:dcfacfc
---

## What to build

`knot close <id> --summary ... --external-ref ""` rejects the blank the way `knot update <id> --add-external-ref ""` already does: exit 1, message `--external-ref value must not be blank`, `{ok:false, error:{code:"invalid_argument"}}` under `--json`, and no write — the ticket stays open, no summary lands.

Today close silently drops the blank (exit 0, ticket closed, `external_refs: []`), and `knot close --help` even says so. Two flags recording the same field should answer a blank the same way, and rejecting matches every other value flag.

Whitespace-only values count as blank; surrounding whitespace on a real value is trimmed, as the update deltas already do.

`knot update --external-ref ""` (replace-all) is a different flag with a different meaning — a single blank clears the list — and is untouched.

The `close` help note claiming a blank value is ignored goes; the flag description says a blank is rejected.

## Blocked by

None - can start immediately

## Notes

**2026-08-27T16:50:46.230596747Z**

close --external-ref "" now exits 1 with --external-ref value must not be blank (invalid_argument under --json) and writes nothing, matching update --add-external-ref. opts* moved inside transition-handler's try and runs through normalize-delta-values; real values are trimmed. update --external-ref "" still clears. close help no longer says a blank is ignored. Shipped in dcfacfc.
