---
id: kno-01kqys6tvsdr
title: 'knot create: missing value for numeric flag coerces implicit-true and reports "Coerce failure" without naming the flag'
status: open
type: bug
priority: 3
mode: hitl
created: '2026-05-06T13:56:30.190644893Z'
updated: '2026-05-06T23:42:47.365543405Z'
tags:
- refine
- v0.4
acceptance:
- title: Root cause confirmed (does babashka.cli bind true for absent value, or does knot's spec allow it?).
  done: false
- title: '`knot create "x" --priority --tags x` errors with a message naming `--priority` and stating an integer value is required; no "(implicit) true" / "Coerce failure" text reaches the user.'
  done: false
- title: Same fix verified to apply to every numeric flag across all commands (mapped failure surface).
  done: false
- title: Decision recorded between (a) per-flag validator in knot, (b) argv pre-process, (c) upstream patch — with rationale.
  done: false
- title: Test covers at least one missing-numeric-value case end-to-end.
  done: false
links:
- kno-01kqxd0amhnb
- kno-01kqn0mtsvpq
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
