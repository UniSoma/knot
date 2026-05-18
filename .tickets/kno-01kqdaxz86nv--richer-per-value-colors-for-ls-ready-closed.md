---
id: kno-01kqdaxz86nv
title: Richer per-value colors for ls/ready/closed/blocked tables (type, priority, mode)
status: open
type: feature
priority: 1
mode: hitl
created: '2026-04-29T19:19:54.374266390Z'
updated: '2026-05-18T17:42:26.468362197Z'
links:
- kno-01kqgqapwqvh
- kno-01kqg37mssy3
tags:
- output
- colors
- refine
---

## Description

The list-view tables (`knot ls`, `ready`, `closed`, `blocked`) already colorize the `:status` column per-value (role-derived from `:terminal-statuses` / `:active-status` per kno-01kqg37mssy3), but `:type`, `:mode`, and `:priority` are mostly uniform — `:type` and `:mode` collapse to `[:faint]` regardless of value, and `:priority` only highlights `0`. Extend the same per-value coloring to those three columns.

**Current state:** `src/knot/output.clj` `color-codes-for` (line ~365) returns uniform `[:faint]` for `:type` and `:mode`, and `[:red :bold]` only for `:priority "0"`.

## Resolved design (grilled 2026-05-18)

### Approach: opinionated defaults, no new config surface

Hardcoded color maps for canonical values; custom-config values degrade gracefully to faint. No `:type-colors` / `:mode-colors` config in v1 — additive later if a real downstream need surfaces. The audit-precedent (kno-01kqgqapwqvh) hunted *correctness* bugs (custom statuses must still render), not aesthetic configurability.

### Asymmetric shape: role-indirection only where config can derive it

| Column | Role layer? | Why |
|---|---|---|
| `:type` | No — direct map | `:types` is a flat list with no semantic config; a role function would just relocate the hardcode |
| `:mode` | Yes — `mode-role` | `:afk-mode` config marks the agent-runnable mode |
| `:priority` | No — direct ordinal | The value 0..4 *is* the role |

### `mode-role` (new, public, parallels `status-role`)

Signature: `(mode-role mode modes default-mode afk-mode)` → one of `:afk` / `:default` / `:other`.

- `:afk` — mode equals `:afk-mode`
- `:default` — mode equals `:default-mode` and is not `:afk-mode`
- `:other` — configured in `:modes` but neither (and unknown modes)

Edge: when `:afk-mode` is `nil` (per `config.clj:127-132`, disables the AFK preamble entirely), no mode resolves to `:afk`.

### Palette

Extend `ansi-codes` with `:green` (32), `:blue` (34), `:magenta` (35).

**type** (direct map by canonical name + `[:faint]` fallback):
- `bug` → `[:red]`
- `feature` → `[:green]`
- `task` → `[]` (plain — *not* cyan; would clash with `:open` status, the most common row)
- `epic` → `[:magenta]`
- `chore` → `[:faint]`
- unknown → `[:faint]`

**priority** (direct ordinal):
- `0` → `[:red :bold]` (keep)
- `1` → `[:yellow]`
- `2` → `[]` (plain — most common)
- `3` → `[:faint]`
- `4` → `[:faint]` (collapsed with 3; `:faint` and `:dim` share ANSI code "2")

**mode** (by role):
- `:afk` → `[:blue]`
- `:default` → `[]` (plain)
- `:other` → `[:faint]`

Accepted clashes (intentional or harmless):
- bug-red ↔ priority-0 red+bold: reinforcing ("high-priority bug" panic).
- priority-1 yellow ↔ `:active` status yellow: different columns; both signal attention.
- chore-faint ↔ `:terminal` status faint: reinforcing for closed chores.

### Plumbing

Rename `status-context` → `render-ctx` in `color-codes-for` and `ls-table`. `render-ctx` holds `:statuses :terminal-statuses :active-status :modes :default-mode :afk-mode`. Single thread through ls-table; fallback via `config/defaults` preserves v0 schema.

Extend `cli/ls-table-opts` to include the three mode keys; symmetric wiring across `ls-cmd` / `ready-cmd` / `closed-cmd` / `blocked-cmd`.

### Out of scope

ID, ASSIGNEE, AGE, ACCEPTANCE, TITLE columns. Prime output stays monochrome.

## Test plan

**New pure-function tests:**
- `mode-role-test` — cases: `:afk`, `:default`, `:other`, unknown mode, `:afk-mode nil` edge.

**New table-output tests:**
- `ls-table-default-modes-color-roles-test` — default `["afk" "hitl"]`; afk row blue, hitl plain.
- `ls-table-custom-modes-color-roles-test` — `["agent" "human" "review"]`, `:afk-mode "agent"`, `:default-mode "human"`; roles come from config not literal names.
- `ls-table-type-color-test` — each canonical type wraps in expected SGR; unknown type faint.
- `ls-table-priority-color-test` — 0/1/2/3/4 wrap correctly; 3 and 4 collapse to faint by design.
- `ls-table-no-mode-options-back-compat-test` — defaults fallback.

**Wiring tests** (parallel to `list-cmds-thread-status-context-test`):
- Pin that `ready`/`closed`/`blocked` thread `:modes :default-mode :afk-mode`, using a custom-config end-to-end so the v0 fallback wouldn't satisfy the assertion.

**Existing tests to update:**
- Any `output_test.clj` assertions on `color-codes-for` returning `[:faint]` for `:type`/`:mode` or `[]` for non-zero `:priority`.

## Implementation steps

1. Add `:green` / `:blue` / `:magenta` to `ansi-codes` (`output.clj:66-74`).
2. Add public `mode-role` defn next to `status-role` (`output.clj:334-357`).
3. Add `type->codes`, `priority->codes`, `mode-role->codes` constant maps.
4. Update `color-codes-for` (`output.clj:365-373`) to consume `render-ctx`; rename binding in `ls-table` (`output.clj:461-466`).
5. Extend `cli/ls-table-opts` to include the three mode keys; verify all four list commands pick them up.
6. Update / add tests per plan above.
7. Eyeball against a real terminal — both light and dark backgrounds.

## Notes

**2026-05-01T03:11:26.695691072Z**

Design recommendation superseded — apply role-derivation pattern from kno-01kqg37mssy3 (status colors derived from :statuses / :active-status / :terminal-statuses, not from literal status names). Do not hardcode color literals keyed by canonical config values; that pattern has now been recognized as the recurring 'hardcoded-canonical-config-literals' bug source three times. Open question #1 (':type-colors' map in .knot.edn) becomes the right shape, possibly with role-based defaults for the canonical types/modes/priorities. See kno-01kqgqapwqvh for the broader audit.

**2026-05-14T00:05:25.725717187Z**

Triage 2026-05-14: upstream pattern dependency kno-01kqg37mssy3 (status color role-derivation) has shipped/closed — the design recommendation from the prior note can now be applied. Re-read the canonical role-derivation pattern in src/knot/output.clj before designing :type/:priority/:mode color maps.
