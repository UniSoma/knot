---
id: kno-01kr0129m0y9
title: Pre-extract dash-leading-safe handling for value-bearing string flags
status: closed
type: task
priority: 3
mode: afk
created: '2026-05-07T01:33:04.505238004Z'
updated: '2026-05-17T03:14:15.000968825Z'
closed: '2026-05-17T03:01:49.451537761Z'
acceptance:
- title: extract-value-flags map is derived from knot.help/registry, not hand-maintained per handler.
  done: true
- title: 'Tests cover the four shapes per flag: single-line dash-leading, double-dash-leading, alias-shaped ("-x"), and multi-line bullet list.'
  done: true
- title: 'kno-01kqxd0amhnb''s AC #6 hint is reviewed against the post-fix surface and trimmed if it advertises workarounds that are no longer needed.'
  done: true
- title: 'Every value-bearing string flag survives dash-leading values, covering: create (--type, --assignee, --external-ref, --parent, --tags, --mode, --acceptance, --dep, --link); update (--title, --type, --mode, --assignee, --parent, --tags, --add-tag, --remove-tag, --external-ref, --ac, --add-ac, --remove-ac); status/close (--summary); list/ready/blocked/closed (--status, --assignee, --tag, --type, --mode); prime (--mode, --status, --assignee, --tag, --type); init (--prefix, --tickets-dir); check (--severity, --code).'
  done: true
- title: extract-body-flags is kept as-is alongside the new extract-value-flags; body-flag->key remains the command-agnostic union powering help-requested? for body values. No regression on --description / --design / --body.
  done: true
- title: 'Aliases are derived from the :alias keyword on each flag spec (registry-driven, not hand-coded). Coverage test: -d, -t, -a all accept dash-leading values.'
  done: true
links:
- kno-01kqxd0amhnb
- kno-01krsyn5v7sq
- kno-01krsytqv33a
---

## Description

Generalise the existing `extract-body-flags` pattern (src/knot/main.clj:82-112) to cover every value-bearing string flag, so dash-leading values like `--acceptance "- text"`, `--summary "-cancelled"`, `--tags "-foo"` survive parsing. babashka.cli's `parse-key` (cli.cljc:344-367) treats any argv token starting with `-` as a flag name even after `=`, with no upstream escape that works (full surface map and root-cause analysis: kno-01kqxd0amhnb).

## Design

### Approach

Drive the per-command extract map from `knot.help/registry` rather than maintaining it by hand. Add `extract-value-flags` **alongside** `extract-body-flags` (not subsumed — see below). For each command handler:

1. Build `flag-map` from `(:flags (get registry cmd-key))` by selecting flags where `:coerce` is not `:boolean`, not `:long`, not `[:long]`, and `:body?` is not `true`. Carry the `:coerce` shape alongside so the loop knows whether to accumulate (`[]`) or replace.
2. For each selected flag `{:name :foo :alias :a}`, emit two flag-map entries: `"--foo" → :foo` and (when `:alias`) `"-a" → :foo`. Derive aliases by `(when alias (str "-" (name alias)))`.
3. Loop over argv with the same shape as `extract-body-flags` (long form and `=` form). On match for a `[]`-coerce flag, conj into a vector under the key; otherwise assoc (last-wins, matching babashka.cli semantics).
4. Hand the residual argv to `bcli/parse-args` as today; merge extracted opts on top of the parser output (extracted wins, since the parser never saw the flag).

Touched handlers: `init-handler`, `create-handler`, `update-handler`, `ls-handler`/`list-handler`, `transition-handler` (for `--summary` on status/close), `dep-handler`, `check-handler`, `prime-handler`.

### Why keep `extract-body-flags` separate

`extract-body-flags` already works, and `help-requested?` (src/knot/main.clj:113-118) depends on the **command-agnostic** `body-flag->key` union to suppress `--help` detection inside body values. Subsuming would force `help-requested?` to consume the union of every command's value-flag map, which is wider scope than this ticket needs. Two focused extractors over one general-purpose one.

### Alias derivation

Registry stores aliases as keywords on `:flags` entries (`:alias :t`, `:alias :d`, `:alias :a`, `:alias :p`). The `:coerce :long` exclusion filters out `-p` (aliases `--priority`); operationally only `-d`/`-t`/`-a` survive into the extract maps today, but the derivation must remain general so newly added aliased string flags get picked up automatically.

## Out of scope

Positional args starting with `-` (e.g. `knot status <id> -cancelled`, `knot add-note <id> "- text"`). Pre-extract needs a flag token to anchor on, so positionals are not addressed here. Mitigation lives in the better error message shipped under kno-01kqxd0amhnb (AC #6) — the hint will tell users when they hit this case. A separate ticket can address positional handling if it proves to matter in practice.

## Tests

- For each (command, flag) pair listed in AC #1: a test passing `"- text"`, `"--text"`, `"-x"`, and the multi-line shape `"- one\n- two\n- three"` and asserting the value lands intact (via `knot show` or direct opts inspection).
- Alias coverage: confirm `-d "- text"`, `-t "- bug"`, `-a "- user"` survive on create.
- Repeatable flags (`--acceptance`, `--external-ref`, `--add-tag`, `--remove-tag`, `--add-ac`, `--remove-ac`, `--dep`, `--link`, list-filter flags): assert multi-occurrence accumulates into the expected vector with dash-leading values intact.
- Existing body-flag tests must keep passing (no regression on `--description` / `--design` / `--body`).
- Round-trip: dash-leading values created via `knot create --acceptance` show up correctly under `knot show`.
- `prime` silent-acceptance regression: `knot prime --status -open` reaches the filter (or errors loudly) — not silently dropped.

## Notes

**2026-05-17T03:01:49.451537761Z**

Added registry-driven extract-value-flags in src/knot/main.clj alongside extract-body-flags; wired into create / update / transition (status,close) / ls / list / init / check / prime handlers. value-flag-map derives per-command flag tokens from (:flags (get help/registry cmd-key)), skipping :body?/:boolean/:long/[:long] and deriving aliases from :alias. Loop mirrors extract-body-flags shape with conj-on-:coerce-[] semantics. Dash-leading hint message trimmed (workaround line removed; function kept as defensive fallback). 5 new integration deftests cover the four shapes (single-line, double-dash, alias-shaped, multi-line bullet list) plus the AC-listed flag surface across every handler. 374 tests / 4630 assertions, lint clean.
