---
id: kno-01kqzkhpc244
title: v0.4 release plan and coordination
status: open
type: chore
priority: 2
mode: hitl
created: '2026-05-06T21:36:48.975272046Z'
updated: '2026-05-06T23:42:47.365543405Z'
tags:
- v0.4
- release
deps:
- kno-01kqzh1tadw8
- kno-01kqzh2vhhrz
- kno-01kqzh3jgwf0
- kno-01kqys929mdy
- kno-01kqe9ytd40z
- kno-01kqn3swv94c
- kno-01kqxd0amhnb
- kno-01kqys6tvsdr
---

## Description

Coordination ticket for the v0.4 release cycle. Mirrors kno-01kqgqfwk4h1 (v0.3 release plan and coordination). Close when v0.4 is tagged and shipped.

## Slices

**Release tooling completion** (filed during v0.3 cut as follow-ups):
- kno-01kqzh1tadw8 — `knot check` warning for legacy `## Acceptance Criteria` body section
- kno-01kqzh2vhhrz — Modernize `/release` slash command for v0.3-shape cuts
- kno-01kqzh3jgwf0 — Release-tag smoke CI workflow (bbin install + golden-path on tag push)

**Listings / prime UX** (cross-linked p2 cluster, single code path):
- kno-01kqys929mdy — `knot prime`: modernize directive and runtime sections
- kno-01kqe9ytd40z — Age column on list/ready/blocked
- kno-01kqn3swv94c — Tags column on listing views

**`knot create` parser cleanup** (paired p3 bugs, same tools.cli wrapping):
- kno-01kqxd0amhnb — Dash-leading values for string flags fail with cryptic error
- kno-01kqys6tvsdr — Missing value for numeric flag coerces implicit-true and reports "Coerce failure" without naming the flag

## Out of scope (deferred to v0.5+)

- Dep-tree cluster: kno-01kqn0ewrp71, kno-01kqn0fz9xjy
- `knot capture` TUI: kno-01kqn0hzgkqq
- Larger features deferred for sizing: kno-01kqxgt2jkf2 (stats), kno-01kqe8kjmrpp (ai setup), kno-01kqe94cgmd2 (Pi packaging), kno-01kqdaxz86nv (richer colors)
- Future-tagged: kno-01kqcpb0t5s7 (distribution refinement), kno-01kqgqaxzx98 (concurrency control)
