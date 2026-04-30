---
id: kno-01kqg37mssy3
title: Status color map hardcodes 'in_progress'/'open'/'closed' — breaks custom :statuses
status: open
type: bug
priority: 3
mode: hitl
created: '2026-04-30T21:03:06.041382403Z'
updated: '2026-04-30T21:03:14.340121261Z'
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
