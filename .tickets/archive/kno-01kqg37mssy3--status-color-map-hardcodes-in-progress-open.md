---
id: kno-01kqg37mssy3
title: Status color map hardcodes 'in_progress'/'open'/'closed' — breaks custom :statuses
status: closed
type: bug
priority: 3
mode: hitl
created: '2026-04-30T21:03:06.041382403Z'
updated: '2026-04-30T22:05:48.740461367Z'
closed: '2026-04-30T22:05:48.740461367Z'
links:
- kno-01kqdat9xssc
---

## Description

After kno-01kqdat9xssc made knot start / prime / cheatsheet read :active-status from config, projects with :statuses ["open" "active" "closed"] are fully functional but the ls/ready/closed/blocked tables render the active lane uncolored.

`color-codes-for` in src/knot/output.clj:227-238 dispatches on the literal status strings "open" / "in_progress" / "closed". A status like "active" or "review" gets no color; only the three default status names are styled. This is now the next-most-visible 'custom statuses break things' gap surfaced by the active-status fix.

Surfaced by the code review on commit dfa4137.

## Design hints

- Could derive colors from :statuses ordering + :terminal-statuses + :active-status (e.g. terminal -> :dim, active -> :yellow, first non-active non-terminal -> :cyan, rest -> default).
- Or: keep a literal map but key it by *role* ('open' / 'active' / 'terminal') and resolve role per ticket from config at render time.
- Related but distinct from kno-01kqdaxz86nv (which is about per-value colors for type/priority/mode, not status).

## Notes

**2026-04-30T21:37:48.021665925Z**

Implemented role-based status colors. Added knot.output/status-role (public): resolves status → :terminal/:active/:open/:other from :statuses + :terminal-statuses + :active-status. Default mapping is :terminal→:dim, :active→:yellow, :open→:cyan (first non-active non-terminal in :statuses ordering), :other→no color. Threaded :statuses/:terminal-statuses/:active-status through output/ls-table opts (defaults preserve v0 schema), and wired ls-cmd/ready-cmd/closed-cmd/blocked-cmd via a small private ls-table-opts helper in cli.clj. Tests added under TDD across both layers: status-role-test (5 cases: terminal, active, open, custom-with-other, ordering, unknown-status), ls-table-{default,custom}-statuses-color-roles-test, ls-table-no-status-options-back-compat-test, and an end-to-end ls-cmd-custom-statuses-color-test that drives create→start→ls under a custom :active-status "active" ctx and asserts the active lane wraps in yellow SGR (and that literal in_progress no longer drives color). Full suite: 197 tests, 1657 assertions, 0 failures.

**2026-04-30T22:05:48.740461367Z**

Replaced the literal status→color case in output/color-codes-for with role-based dispatch driven by config. New public knot.output/status-role resolves a status to :terminal/:active/:open/:other from (statuses, terminal-statuses, active-status); roles map to ANSI codes (terminal→:dim, active→:yellow, open→:cyan, other→no color). Threaded :statuses/:terminal-statuses/:active-status through output/ls-table opts (defaults preserve v0 schema verbatim), and wired ls-cmd/ready-cmd/closed-cmd/blocked-cmd via a new private cli/ls-table-opts helper. Tests added under TDD across both layers: status-role-test (5 cases incl. ordering precedence and unknown status), ls-table-{default,custom}-statuses-color-roles-test, ls-table-no-status-options-back-compat-test, ls-cmd-custom-statuses-color-test, and a list-cmds-thread-status-context-test pinning the symmetric wiring for ready/closed/blocked (closed sub-test uses a custom terminal-status name 'done' so the v0 fallback would not satisfy the assertion — regression-pin verified by temporarily reverting each call site). init-cmd stub gained a 4-line comment above :statuses describing the role→color contract. Final suite: 198 tests, 1663 assertions, 0 failures.
