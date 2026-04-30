---
id: kno-01kqdat9xssc
title: knot start hardcodes 'in_progress' as transition target — breaks projects with custom :statuses
status: closed
type: bug
priority: 1
mode: hitl
created: '2026-04-29T19:17:54.233556456Z'
updated: '2026-04-30T21:03:14.340121261Z'
closed: '2026-04-30T20:44:54.870433523Z'
links:
- kno-01kqdasr0384
- kno-01kqg37mssy3
---

## Description

`knot start` and the prime `## Commands` cheatsheet line that documents it both hardcode the literal status `"in_progress"`. On any project whose `:statuses` does not include `"in_progress"` (e.g. `["open" "active" "closed"]`), `knot start` fails validation, the primer documents a target status that does not exist, and the `## In Progress` filter in `knot prime` silently drops tickets in the project's actual active lane.

**Root cause:**
- `src/knot/cli.clj:211-214` — `start-cmd` is sugar that calls `status-cmd` with `:status "in_progress"` literally.
- `src/knot/cli.clj:619-632` — `in-progress-tickets` filters the prime `## In Progress` block with `(= "in_progress" ...)`. Same root cause as `start-cmd`; on custom-statuses projects, this silently hides tickets in the active lane.
- `src/knot/output.clj:392-399` — `prime-commands-cheatsheet` is a static string with `transition to in_progress` baked in.
- `src/knot/help.clj:211-225` — `:start` description / examples and the `:status` example all hardcode `in_progress`.

## Design

Resolved via grill-me on 2026-04-30. Decisions:

1. **New config key `:active-status`** (rejecting "implicit fallback on `:statuses` order" and "drop the sugar"). Names the *concept* (active lane), not the command, because there are two consumers (`start-cmd` and the prime in-progress filter). Allowed to equal `(first :statuses)` — `knot start` becomes a no-op naturally via `store/save!` in that case.

2. **Default value `"in_progress"`**, added to `config/defaults` and `config/known-keys`.

3. **Strict validation at config load time.** `validate!` throws when `:active-status` is not in `:statuses` or is in `:terminal-statuses`. Error message must name the offending value, the valid set, and the fix ("set `:active-status` explicitly when customizing `:statuses`"). Mirrors how `:default-type` validates against `:types` today.

4. **Scope reaches both consumers.** `start-cmd` and `in-progress-tickets` both read `:active-status` from ctx. Same key drives both — separate keys would be a feature looking for a use case.

5. **`## In Progress` heading and `:in_progress` JSON key stay literal.** They're structural contracts with AI agents, not status-value displays. Same posture the schema-fix ticket settled on.

6. **Help text generalized in place.** Examples in `help.clj` keep the literal `"in_progress"` only where they illustrate the default — `:start :description` becomes `"Transition a ticket to the project's active status (default: in_progress)."` No threading of config through the static `registry` def.

7. **Renderer signature follows the schema-fix pattern: data arg + function.** `prime-commands-cheatsheet` becomes `(defn- prime-commands-cheatsheet [active-status] ...)`. `prime-text`'s data map gains `:active-status`. `prime-cmd` reads it from `resolve-ctx` and threads it in alongside `:terminal-statuses`/`:prefix`/`:project-name`. The no-project branch falls back to `(:active-status (config/defaults))` so the cheatsheet always renders. Cheatsheet line: `knot start <id>                  transition to <active-status>`.

8. **Init stub change.** `stub-config` in `cli.clj` gains an uncommented entry between `:terminal-statuses` and `:types`, with a comment that names both consumers, states the constraint, and prompts the user to update the key when they edit `:statuses`.

## Scope of fix

1. `config.clj`: add `:active-status "in_progress"` to `default-config`, add to `known-keys`, extend `validate!` with the in-`:statuses` / not-in-`:terminal-statuses` rule and a clear error message.
2. `cli.clj` `resolve-ctx`: surface `:active-status` (alongside the existing `:terminal-statuses`).
3. `cli.clj` `start-cmd` (211-214): read `:active-status` from ctx, drop the literal.
4. `cli.clj` `in-progress-tickets` (619-632): filter by `:active-status` from ctx (or threaded arg), drop the literal.
5. `cli.clj` `prime-cmd` (648+): include `:active-status` in the data map passed to `prime-text` / `prime-json`. No-project branch falls back to `(config/defaults)`.
6. `output.clj` `prime-commands-cheatsheet` (392-399): convert from `def` to `(defn- ... [active-status] ...)`. Update `prime-text` to call it. Reword the `prime-text` docstring at 466 to refer to the active-lane concept rather than the literal value.
7. `help.clj` (211-225): reword `:start :description` and example notes per Decision 6. `:status` example stays literal (illustrates the default).
8. `cli.clj` `stub-config` (707-757): insert `:active-status "in_progress"` between `:terminal-statuses` and `:types` with the explanatory comment from Decision 8.

## Test scope

All six categories committed:

- `config_test.clj` — defaults include `:active-status "in_progress"`; throws on out-of-`:statuses`; throws on `:active-status` ∈ `:terminal-statuses`; passes when `:active-status = (first :statuses)`; user override wins on merge.
- `cli_test.clj` — `start-cmd` writes the configured `:active-status` (custom and default).
- `cli_test.clj` — `in-progress-tickets` filters by configured `:active-status`.
- `output_test.clj` — `prime-text` cheatsheet line reflects the data-arg `:active-status`; no-project default fallback renders `transition to in_progress`.
- `integration_test.clj` — end-to-end on a `:statuses ["open" "active" "closed"] :active-status "active"` project (`init`, `create`, `start`, `prime` shows the ticket under `## In Progress`); negative test for `.knot.edn` that overrides `:statuses` without `:active-status` (strict-validation error surfaces).
- Init-stub-contents test — generated `.knot.edn` contains the `:active-status "in_progress"` line.

## Assumptions / non-goals

- Renderer requires `:active-status` in its data arg; the caller handles the no-project fallback via `(config/defaults)`.
- `stale-in-progress?` and `prime-in-progress-nudge` need no changes — staleness operates post-filter, and the nudge text doesn't mention the literal value.
- JSON shape of `prime-json` does not change (`:in_progress` key stays).
- Internal Clojure symbols (`in-progress-tickets`, `stale-in-progress?`, `prime-in-progress-nudge`) keep their names — they refer to the concept, same as the `## In Progress` heading.
- Pre-1.0, no users — strict validation can land without a deprecation cycle. No CHANGELOG migration note needed beyond a normal release entry.

## Related

- kno-01kqdasr0384 (closed) — schema-fix ticket. Established the data-arg pattern for `prime-text` that this ticket extends.

## Notes

**2026-04-30T20:44:54.870433523Z**

Added :active-status config key (default: in_progress) with strict validation that it must be in :statuses and not in :terminal-statuses. start-cmd and the prime in-progress filter both read it from ctx; the prime Commands cheatsheet renderer takes it as a data arg (def→defn-, schema-fix pattern), with no-project fallback to (config/defaults). Init stub gains an uncommented :active-status entry with an explanatory comment naming both consumers. ## In Progress heading and :in_progress JSON key stay literal — structural contracts. Help.clj :start description generalized to 'project active status (default: in_progress)'. Tests committed across all six categories: config, cli (start-cmd + in-progress filter), output (cheatsheet active-status + no-project fallback), integration (end-to-end custom :statuses, plus negative test for missing :active-status), and init stub contents.
