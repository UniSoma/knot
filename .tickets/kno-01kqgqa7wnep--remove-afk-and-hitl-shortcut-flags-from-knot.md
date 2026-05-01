---
id: kno-01kqgqa7wnep
title: Remove --afk and --hitl shortcut flags from knot create
status: open
type: chore
priority: 3
mode: afk
created: '2026-05-01T02:54:02.645480566Z'
updated: '2026-05-01T02:54:02.645480566Z'
tags:
- v0.3
- cli
- cleanup
- needs-triage
---

## Description

Remove the `--afk` and `--hitl` shortcut flags from `knot create`. They are aliases for `--mode afk` / `--mode hitl` but bake the canonical mode names into CLI parsing — same hardcoded-canonical-config-literals pattern fixed in kno-01kqdat9xssc (`:active-status`).

On a project that customizes `:modes` to drop `afk` or `hitl` (e.g. `:modes ["solo" "team"]`), the shortcut flags reference modes the project does not have. They either error confusingly or silently write a value validation will reject.

After this slice, `--mode <value>` is the only path to set the mode on `knot create`. Add a comment to the `:modes` section of the init stub forbidding new mode-shortcut flags as `:modes` grows.

Pre-1.0 break window — no deprecation cycle.

## Acceptance Criteria

- [ ] `--afk` flag removed from `knot create`
- [ ] `--hitl` flag removed from `knot create`
- [ ] Help text updated to remove the shortcut entries
- [ ] Init-stub `.knot.edn` template has a comment under `:modes` forbidding shortcut flags ("use --mode <value> always; do not add per-mode shortcut flags")
- [ ] Tests updated; any test using `--afk`/`--hitl` migrated to `--mode <value>`
- [ ] CHANGELOG entry notes the breaking change
