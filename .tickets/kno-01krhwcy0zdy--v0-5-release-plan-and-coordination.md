---
id: kno-01krhwcy0zdy
title: v0.5 release plan and coordination
status: in_progress
type: chore
priority: 2
mode: hitl
created: '2026-05-13T23:57:49.937527823Z'
updated: '2026-05-17T01:17:02.008636465Z'
tags:
- v0.5
- release
deps:
- kno-01kqzh2vhhrz
- kno-01kqzh3jgwf0
- kno-01kqe9ytd40z
- kno-01kqys6tvsdr
links:
- kno-01kqzkhpc244
---

## Description

Coordination ticket for the v0.5 release cycle. Mirrors kno-01kqzkhpc244 (v0.4 release plan). Close when v0.5 is tagged and shipped.

## Slices (carried over from v0.4)

Four v0.4-plan blockers slid to v0.5 (retagged v0.4 → v0.5 at v0.4 cut time):

- kno-01kqzh2vhhrz — Modernize `/release` slash command for v0.3-shape cuts
- kno-01kqzh3jgwf0 — Release-tag smoke CI workflow (bbin install + golden-path on tag push)
- kno-01kqe9ytd40z — Age column on list/ready/blocked
- kno-01kqys6tvsdr — `knot create`: missing value for numeric flag coerces implicit-true

## Context

v0.4.0 was cut manually 2026-05-13 (commit 932e2b5, lightweight tag) around the still-unmodernized `/release` command — same one-off as v0.3. Closing out the release tooling slices (especially kno-01kqzh2vhhrz) is the dominant motivation for this cycle so v0.5 can be the first cut to actually run `/release` cleanly.

Additional v0.5 scope to be added as it firms up.
