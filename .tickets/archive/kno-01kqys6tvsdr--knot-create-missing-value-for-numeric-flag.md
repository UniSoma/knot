---
id: kno-01kqys6tvsdr
title: 'knot create: missing value for numeric flag coerces implicit-true and reports "Coerce failure" without naming the flag'
status: closed
type: bug
priority: 3
mode: hitl
created: '2026-05-06T13:56:30.190644893Z'
updated: '2026-05-15T18:56:08.419629294Z'
closed: '2026-05-15T18:56:08.419629294Z'
tags:
- refine
- v0.5
acceptance:
- title: Root cause confirmed (does babashka.cli bind true for absent value, or does knot's spec allow it?).
  done: true
- title: '`knot create "x" --priority --tags x` errors with a message naming `--priority` and stating an integer value is required; no "(implicit) true" / "Coerce failure" text reaches the user.'
  done: true
- title: Same fix verified to apply to every numeric flag across all commands (mapped failure surface).
  done: true
- title: Decision recorded between (a) per-flag validator in knot, (b) argv pre-process, (c) upstream patch — with rationale.
  done: true
- title: Test covers at least one missing-numeric-value case end-to-end.
  done: true
links:
- kno-01kqxd0amhnb
- kno-01kqn0mtsvpq
parent: kno-01krhwcy0zdy
---

## Description

Long-typed flags (`--priority`, and likely any other numeric flag) given no value silently default to boolean `true` in `babashka.cli`, which then fails coercion with a message that doesn't mention the offending flag.

Repro:

```bash
knot create "x" --priority --tags x
# stderr: knot: Coerce failure: cannot transform (implicit) true to long
# exit 1
```

Expected: an error that names the flag, e.g. `--priority requires an integer value (0-4); none provided`.

Real-world impact: surfaced by an external user creating a ticket via shell, who initially misattributed the error to `--acceptance` (the next-listed flag) and lost time on the wrong hypothesis.

Affected:
- Numeric flag specs in `src/knot/help.clj` (`:create` and likely other commands using `:coerce :long`)
- Wherever `babashka.cli/parse-opts` is invoked in `src/knot/main.clj` / `src/knot/cli.clj`

Note: the user's report also bundled a second repro (multi-line value containing `\n-` produces `Unknown option: :`). That second case is most plausibly a multi-line manifestation of kno-01kqxd0amhnb (dash-leading value parsing), so it is **not** in scope for this ticket — captured as a note on kno-01kqxd0amhnb instead.

## Notes

**2026-05-15T18:54:28.840599005Z**

Fix landed via missing-value-coerce-mishap? in src/knot/main.clj (parallel to dash-leading-value-mishap?). Predicate matches the bb-cli signature {:type :org.babashka/cli :cause :coerce :msg ~'(implicit) '}; the catch reads :option and :spec from ex-data to format a flag-naming message. The (implicit) substring cleanly distinguishes missing-value from legit-but-unparseable values (bb-cli emits 'input "abc"' instead). Decision (AC #4): option (a)-shaped — top-level catch-and-rewrite. Rationale: bb-cli's ex-data already carries :option and :spec, so no argv pre-processing or per-flag validators needed; the existing catch is the right seam. Failure surface (AC #3): single predicate covers every :coerce :long flag in the registry (currently :priority on create/update, :limit on ls/list/ready/blocked/closed) — same ex-data shape verified via nREPL across all four sub-cases (missing-then-flag, missing-at-end, '--flag=' empty, '--flag abc' must NOT trigger). Tests: missing-numeric-value-error-hint-test in test/knot/integration_test.clj covers create --priority (AC #5 + #2), update --priority (cross-command surface), and the negative case (--priority abc still surfaces bb-cli's Coerce-failure message so the hint does not over-trigger).

**2026-05-15T18:56:08.419629294Z**

Detect bb-cli's missing-numeric-value signature (:cause :coerce + '(implicit) ' in :msg) at the top-level catch in main.clj and emit a message naming the flag (read from ex-data :option) and required coerce type (from ex-data :spec). Parallel to dash-leading-value-mishap?. Covers every :coerce :long flag in the registry; negative case (--priority abc) still surfaces the bb-cli Coerce-failure message untouched.
