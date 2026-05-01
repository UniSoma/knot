---
id: kno-01kqgqbjg012
title: Add --json flag to all mutating commands
status: open
type: task
priority: 2
mode: afk
created: '2026-05-01T02:54:46.272213294Z'
updated: '2026-05-01T02:57:42.442743884Z'
tags:
- v0.3
- json
- cli
- needs-triage
deps:
- kno-01kqgq9vhmvr
---

## Description

Extend `--json` output to every mutating command, eliminating the read-after-write round-trip for agents.

Commands to cover:
- Lifecycle: `create`, `start`, `status`, `close`, `reopen`
- Graph: `dep`, `undep`, `link`, `unlink`
- Notes: `add-note`

`data` is uniformly the touched ticket(s):
- Lifecycle: the post-mutation ticket object (single).
- `dep`/`undep`: the `from` ticket with updated `deps`.
- `link`/`unlink`: array of every touched ticket (variadic input → array of post-mutation tickets, shaped like `list --json`'s `data`).
- `add-note`: the post-mutation ticket including the appended note.

Operation metadata that does not belong on the ticket schema (e.g. `archived_to: ".tickets/archive/..."` from `close`) goes in an optional top-level `meta` slot inside the envelope:

```json
{"schema_version": 1, "ok": true, "data": {...ticket...}, "meta": {"archived_to": "..."}}
```

`init` and `edit` are excluded — `init` is project setup (no ticket), `edit` opens `$EDITOR` (interactive only; non-interactive writes go through the new `knot update` command from kno-? Q6).

## Acceptance Criteria

- [ ] `--json` flag added to: `create`, `start`, `status`, `close`, `reopen`, `dep`, `undep`, `link`, `unlink`, `add-note`
- [ ] Output uses the envelope from kno-01kqgq9vhmvr
- [ ] `data` is the touched ticket (single) for lifecycle commands and `add-note`
- [ ] `data` is the `from` ticket for `dep`/`undep`
- [ ] `data` is an array of touched tickets for `link`/`unlink`
- [ ] `close --json` populates `meta.archived_to` with the archive path
- [ ] Errors emit `{ok: false, error: {...}}` per the envelope contract
- [ ] Tests cover happy path + error path per command
- [ ] CHANGELOG entry covers the new flag set
