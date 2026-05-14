---
id: kno-01kr0129m0y9
title: Pre-extract dash-leading-safe handling for value-bearing string flags
status: open
type: task
priority: 3
mode: hitl
created: '2026-05-07T01:33:04.505238004Z'
updated: '2026-05-14T00:05:17.747752415Z'
acceptance:
- title: Every value-bearing string flag in knot.help/registry survives a dash-leading value (covering --acceptance, --summary, --tags, --add-tag, --remove-tag, --add-ac, --remove-ac, --title, --type, --mode, --assignee, --parent, --external-ref, --status, --tag, --severity, --code, --prefix, --tickets-dir).
  done: false
- title: extract-value-flags map is derived from knot.help/registry, not hand-maintained per handler.
  done: false
- title: Existing extract-body-flags behaviour is preserved (or replaced and subsumed) without regression on --description / --design / --body.
  done: false
- title: 'Tests cover the four shapes per flag: single-line dash-leading, double-dash-leading, alias-shaped ("-x"), and multi-line bullet list.'
  done: false
- title: 'kno-01kqxd0amhnb''s AC #6 hint is reviewed against the post-fix surface and trimmed if it advertises workarounds that are no longer needed.'
  done: false
links:
- kno-01kqxd0amhnb
---

## Description

Generalise the existing `extract-body-flags` pattern (src/knot/main.clj:82-112) to cover every value-bearing string flag, so dash-leading values like `--acceptance "- text"`, `--summary "-cancelled"`, `--tags "-foo"` survive parsing. babashka.cli's `parse-key` (cli.cljc:344-367) treats any argv token starting with `-` as a flag name even after `=`, with no upstream escape that works (full surface map and root-cause analysis: kno-01kqxd0amhnb).

## Design

## Approach

Drive the per-command extract map from `knot.help/registry` rather than maintaining it by hand:

- For each command, collect every flag whose spec is *not* `:coerce :boolean`, not `:coerce :long`, and not `:body? true` (already pre-extracted).
- Emit one `extract-value-flags` call per command handler that consumes both the long form (`--flag value`) and the `=` form (`--flag=value`) plus aliases (`-d`, `-t`, `-a`, `-p`).
- Repeatable flags (`:coerce []`) accumulate; non-repeatable flags take last value (matches babashka.cli semantics).
- Hand off the residual argv to `bcli/parse-args` as today.

Touched handlers: `init-handler`, `create-handler`, `update-handler`, `list-handler`/`ls-handler`, `transition-handler` (for status/close --summary), `dep-handler`, `check-handler`, `prime-handler`. Reuse `extract-body-flags`'s loop shape verbatim.

## Out of scope

Positional args starting with `-` (e.g. `knot status <id> -cancelled`, `knot add-note <id> "- text"`). Pre-extract needs a flag token to anchor on, so positionals are not addressed here. Mitigation lives in the better error message shipped under kno-01kqxd0amhnb (AC #6) — the hint will tell users when they hit this case. A separate ticket can address positional handling if it proves to matter in practice.

## Tests

- For each touched (command, flag) pair: a test passing `"- text"`, `"--text"`, `"-x"`, and the multi-line shape `"- one\n- two\n- three"` and asserting the value lands intact.
- Existing body-flag tests must keep passing (no regression on `--description`/`--design`/`--body`).
- Round-trip: dash-leading values created via `knot create --acceptance` show up correctly under `knot show`.
