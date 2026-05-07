---
id: kno-01kr03rmk9p9
title: Gate terminal transitions on unchecked acceptance criteria
status: closed
type: task
priority: 2
mode: afk
created: '2026-05-07T02:20:13.793049170Z'
updated: '2026-05-07T03:02:13.264650714Z'
closed: '2026-05-07T03:02:13.264650714Z'
tags:
- v0.3
- acceptance
acceptance:
- title: knot.acceptance/complete? returns (every? :done acceptance), vacuously true on empty/nil.
  done: true
- title: knot.acceptance/progress returns [done-count total-count].
  done: true
- title: knot.acceptance/open-titles returns titles of unchecked entries, in original order.
  done: true
- title: status-cmd evaluates the gate predicate (and (seq ac) (not (acceptance/complete? ac))) whenever source = :active-status and target ∈ :terminal-statuses.
  done: true
- title: Gate-firing plain-text error on stderr lists the count, open titles indented, and the --check / --force --summary hint.
  done: true
- title: Exit code is 1 on gate firing.
  done: true
- title: JSON mode emits the v0.3 error envelope with error.code = "acceptance_incomplete", a count message, and error.open_acceptance = [{title}, ...].
  done: true
- title: The --force flag is declared on close, status, and update specs (coerce :boolean, default false).
  done: true
- title: When the gate would fire, --force without a non-blank --summary fails with invalid_argument.
  done: true
- title: Empty or whitespace-only --summary paired with --force fails with invalid_argument.
  done: true
- title: Bypass succeeds with --force --summary "<text>"; stderr emits a warning before the success path; summary is appended as a Notes entry.
  done: true
- title: When the gate would not fire, --force is silently accepted (no-op), and --summary rules are unchanged.
  done: true
- title: update applies AC mutations (--check, --uncheck, --add-ac, --remove-ac) before evaluating the gate; single disk write at the end.
  done: true
- title: Gate skips on empty/nil AC, intake→terminal transitions, and terminal→terminal reclassifications.
  done: true
- title: Reopen preserves AC state; subsequent close attempts re-evaluate the gate.
  done: true
- title: Predicate unit tests cover empty/nil AC, all-done, mixed, all-undone.
  done: true
- title: Gate integration tests cover firing on close, status <id> <terminal>, update --status <terminal>; skipping on empty AC, open→closed, terminal→terminal.
  done: true
- title: Bypass path tested for plain text and JSON; missing-summary failure tested for both surfaces.
  done: true
- title: Reopen → close cycle test confirms gate re-fires on the second close attempt.
  done: true
- title: references/json-protocol.md updated with the acceptance_incomplete code row and open_acceptance field documentation.
  done: true
- title: .claude/skills/knot/SKILL.md updated with the --force flag, gate behavior, and new error code in the same commit.
  done: true
- title: CHANGELOG entry under [Unreleased]/Changed (close behavior) and [Unreleased]/Added (--force flag, acceptance_incomplete code).
  done: true
- title: README acceptance-criteria section mentions the gate and the --force --summary escape.
  done: true
- title: bb test passes; clj-kondo --lint src test baseline preserved.
  done: true
links:
- kno-01kqcvp72htb
---

## Description

AC moved out of body markdown into structured frontmatter in v0.3 because they're load-bearing state, not narrative. Until now they're storage-only. This ticket makes them contractual: closing work means the work is done. `--force --summary "..."` is the escape hatch.

## Design

The gate fires at the `status-cmd` chokepoint (single funnel for `close`, `status`, `update --status`). Predicate: `(and (seq ac) (not (acceptance/complete? ac)))`. Fires only on `:active-status → :terminal-statuses` transitions — intake→terminal and terminal→terminal skip.

`--force` is a general-purpose bypass flag (`:coerce :boolean :default false`) declared on close/status/update; non-firing case is silent no-op. When the gate would fire, `--force` requires a non-blank `--summary` (the summary is appended via the existing `append-note` path and serves as the override record). `update` applies AC mutations before evaluating the gate, so `knot update <id> --check "last AC" --status closed` works in one call.

### Predicate placement

- `knot.acceptance/complete?` — `(every? :done acceptance)`, vacuously true on empty/nil.
- `knot.acceptance/progress` — `[done-count total-count]`.
- `knot.acceptance/open-titles` — titles of unchecked entries, in original order.
- The gate predicate is inlined at `status-cmd` (one call site); not named as a helper.

### Error UX

- Plain-text stderr: `knot close: 3 of 5 acceptance criteria are unchecked:` followed by indented list of open titles, ending with the `--check` / `--force --summary` hint.
- JSON: v0.3 error envelope, code `acceptance_incomplete`, message string + `open_acceptance: [{title}, ...]`.
- Exit code 1.

### Known characteristic

In projects with multi-terminal config (e.g. `:terminal-statuses #{"closed" "wontfix"}`), `in_progress → wontfix` trips the gate. Expected escape: `--force --summary "wontfix: <why>"` — the summary becomes the abandonment record. Default single-terminal config is unaffected. Document in CHANGELOG.

## Notes

**2026-05-07T03:02:13.264650714Z**

v0.3 acceptance gate live on close / status terminal / update --status terminal. --force --summary overrides; --check + --status closes in one disk write. JSON: acceptance_incomplete with open_acceptance. Tests: 337/4336, lint baseline preserved. (see knot show kno-01kr03rmk9p9)
