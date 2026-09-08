---
id: kno-01m21ad0gzs4
title: 'Stale-skill check: warn when the installed skill''s version stamp lags the CLI'
status: open
type: feature
priority: 3
mode: hitl
created: '2026-09-08T20:12:25.503872895Z'
updated: '2026-09-08T20:12:25.609950598Z'
tags:
- cli
- agents
links:
- kno-01m21abe2vqs
---

## Description

Follow-up to the skill-from-CLI umbrella (not a child: deliberately out of its scope). Once `knot skill install` stamps `<!-- installed by knot <version> -->` into SKILL.md, read it back in `knot check` (new check code, e.g. `skill_stale`) and/or in `prime` (one-line nudge to re-run `knot skill install`). Decide which surface owns the warning and whether a missing stamp counts as stale.
