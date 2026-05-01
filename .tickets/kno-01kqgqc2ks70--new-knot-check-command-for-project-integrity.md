---
id: kno-01kqgqc2ks70
title: New knot check command for project integrity validation
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-01T02:55:02.777819341Z'
updated: '2026-05-01T02:57:42.846895432Z'
tags:
- v0.3
- cli
- validation
- needs-triage
deps:
- kno-01kqgq9vhmvr
---

## Description

Add `knot check [<id>...]` — a single command that validates project integrity and surfaces issues.

With no ids, scans all tickets (live + archive) and config. With ids, scans just those tickets and any global checks. The command absorbs and replaces `knot dep cycle` (which is removed in this slice — its existence implied an invariant gap, but cycles can be introduced via hand-edits, so the check is real and belongs alongside other integrity checks).

Initial check codes:
- `dep_cycle` — error
- `unknown_id` — error (dangling `deps`/`links`/`parent` references)
- `invalid_status` — error (status not in `:statuses`)
- `invalid_type` — error
- `invalid_mode` — error
- `invalid_priority` — error (outside 0..4)
- `terminal_outside_archive` — error (terminal-status ticket sitting outside `archive/`, or non-terminal in `archive/`)
- `missing_required_field` — error (id, title, status)
- `frontmatter_parse_error` — error
- `invalid_active_status` — error (mirrors the kno-01kqdat9xssc constraint at scan time)

JSON output via the envelope from kno-01kqgq9vhmvr:

```json
{"schema_version": 1, "ok": false, "data": {"issues": [{"severity": "error", "code": "dep_cycle", "ids": [...], "message": "..."}, ...], "scanned": {"live": 10, "archive": 20}}}
```

CLI:

```
knot check [<id>...] [--json] [--severity error|warning] [--code <code>]
```

Exit codes:
- 0 — no errors (warnings allowed)
- 1 — one or more errors detected
- 2 — unable to scan (config invalid, tickets dir missing)

Stretch (optional in this slice; otherwise file follow-up): `stale_in_progress` warning that consolidates `prime`'s nudge logic.

## Acceptance Criteria

- [ ] `knot check` command implemented; routes through the envelope from kno-01kqgq9vhmvr
- [ ] All initial check codes implemented (`dep_cycle`, `unknown_id`, `invalid_status/type/mode/priority`, `terminal_outside_archive`, `missing_required_field`, `frontmatter_parse_error`, `invalid_active_status`)
- [ ] Each issue carries `severity`, `code`, `ids`, `message`; field/value where applicable
- [ ] `--severity` and `--code` filter flags work (repeatable)
- [ ] Exit codes: 0 clean / 1 errors / 2 unable-to-scan
- [ ] `knot dep cycle` removed; help and docs link `dep cycle` users to `knot check`
- [ ] Tests cover at least one positive case per check code
- [ ] CHANGELOG covers the new command and the `dep cycle` removal
