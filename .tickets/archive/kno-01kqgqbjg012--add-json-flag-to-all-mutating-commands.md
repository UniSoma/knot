---
id: kno-01kqgqbjg012
title: Add --json flag to all mutating commands
status: closed
type: task
priority: 2
mode: afk
created: '2026-05-01T02:54:46.272213294Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-05-02T18:46:56.062007681Z'
tags:
- v0.3
- json
- cli
- needs-triage
deps:
- kno-01kqgq9vhmvr
acceptance:
- title: '`--json` flag added to: `create`, `start`, `status`, `close`, `reopen`, `dep`, `undep`, `link`, `unlink`, `add-note`'
  done: false
- title: Output uses the envelope from kno-01kqgq9vhmvr
  done: false
- title: '`data` is the touched ticket (single) for lifecycle commands and `add-note`'
  done: false
- title: '`data` is the `from` ticket for `dep`/`undep`'
  done: false
- title: '`data` is an array of touched tickets for `link`/`unlink`'
  done: false
- title: '`close --json` populates `meta.archived_to` with the archive path'
  done: false
- title: 'Errors emit `{ok: false, error: {...}}` per the envelope contract'
  done: false
- title: Tests cover happy path + error path per command
  done: false
- title: CHANGELOG entry covers the new flag set
  done: false
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

## Notes

**2026-05-02T18:46:56.062007681Z**

Added --json flag to all 10 mutating commands (create, start, status, close, reopen, dep, undep, link, unlink, add-note), eliminating the read-after-write round-trip for agents. output/envelope-str extended with :meta opts → {schema_version, ok, data, meta} (meta omitted when nil). New helpers output/touched-ticket-json (single, body included, optional meta) and output/touched-tickets-json (array, body excluded, ls-shape). Lifecycle commands and add-note re-load via store/load-one after save! and wrap the post-mutation ticket; close --json (and any status to terminal) populates meta.archived_to with the archive path. dep/undep emit the from ticket post-mutation; link/unlink emit the array of every touched ticket. Error envelopes extend the read-side contract: not_found, ambiguous_id, plus new cycle code (with structured error.cycle path) for dep --json. Help registry got :json flag entries on 10 commands; main handlers (transition/edge/link/unlink/add-note/create) thread :json? through and route resolver failures to the envelope on stdout. Added shared emit-error-envelope! helper. edge-handler arity bumped 3→4 to take cmd-key for spec lookup. Verification: bb test → 245 tests, 2129 assertions, 0 failures (added 12 new test cases across cli_test, output_test, integration_test); clj-kondo baseline unchanged (4 errors / 5 warnings, all pre-existing); live smoke against the binary confirmed create --json, close --summary --json (with meta.archived_to), and not_found error envelope on missing ids. Code review pass: zero Critical/Important issues; applied two minor cleanups (renamed local terminal? → terminal-target? in status-cmd json branch; added one-line note on link/unlink handlers documenting the intentional asymmetry where --json upgrades ambiguous/not-found to envelopes while the non-json path keeps the stderr die message). Skipped reviewer's optional suggestions per their steer: ExceptionInfo dispatch extraction (park unless pattern grows), per-command :json flag-spec consolidation (verbosity is intentional — close/dep descs call out their unique semantics). Bundled .claude/skills/knot/SKILL.md kept in sync per project hard rule; CHANGELOG entry added under [Unreleased] Added.
