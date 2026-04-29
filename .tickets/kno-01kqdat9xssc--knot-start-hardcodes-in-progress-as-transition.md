---
id: kno-01kqdat9xssc
status: open
type: bug
priority: 1
mode: hitl
created: '2026-04-29T19:17:54.233556456Z'
updated: '2026-04-29T19:17:58.427493937Z'
links:
- kno-01kqdasr0384
---

# knot start hardcodes 'in_progress' as transition target — breaks projects with custom :statuses

## Description

`knot start` and the `## Commands` cheatsheet line that documents it both hardcode the literal status `"in_progress"`. On any project whose `:statuses` does not include `"in_progress"` (e.g. `["open" "active" "closed"]`), `knot start` will fail validation and the primer will document a target status that does not exist.

**Root cause:**
- `src/knot/cli.clj:209-211` — `start-cmd` is defined as sugar that calls `status-cmd` with `:status "in_progress"` literally. There is no fallback to a configured "standard non-terminal" status.
- `src/knot/output.clj:380` — the `prime-commands-cheatsheet` line `knot start <id>  transition to in_progress` is part of a static string and is not parameterized by config.
- `src/knot/help.clj:204-213` — the `status` command help block also references `in_progress` literally in its examples and description.

**Open design question (decide as part of the fix):** what does `knot start` mean when a project's `:statuses` does not contain `"in_progress"`?
- Option A: pick the second status in `:statuses` by convention (the first non-terminal status that is not the initial `open`/index-0 status). Implicit and possibly surprising.
- Option B: introduce a new config key (e.g. `:active-status` or `:start-status`) defaulting to `"in_progress"`, validated to be one of `:statuses` and not in `:terminal-statuses`. Explicit, slightly more config surface.
- Option C: drop `knot start` as sugar and require users to call `knot status <id> <status>` directly on non-default-statuses projects. Simplest, worst UX.

Recommend Option B — it keeps the sugar working and the fallback explicit.

**Scope of fix (assuming Option B):**
1. Add `:active-status` (or similar) to `config.clj` defaults and `known-keys`, with validation that it is in `:statuses` and not in `:terminal-statuses`.
2. Update `start-cmd` to read `:active-status` from ctx instead of hardcoding `"in_progress"`.
3. Update `prime-commands-cheatsheet` to be a function of the resolved active status (similar reshape to the `## Schema` ticket).
4. Update help text in `help.clj` to reference the configured value.
5. Update tests + the `init` stub config in `cli.clj` to document the new key.

**Related ticket:** the `## Schema` section hardcode is tracked separately. Both tickets reshape `prime-text` to take more config from the data arg, so coordinate the renderer signature changes.
