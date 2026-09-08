---
id: kno-01m21ad077j1
title: 'knot prime: live pointer sentence depending on whether the skill is installed'
status: open
type: feature
priority: 2
mode: afk
created: '2026-09-08T20:12:25.156663522Z'
updated: '2026-09-08T20:12:25.156663522Z'
parent: kno-01m21abe2vqs
tags:
- cli
- agents
acceptance:
- title: With a skill at any of the three locations, both preambles end with the invoke-the-skill sentence; without one, they end with the knot help topics sentence and a knot skill install nudge
  done: false
- title: :skill-dir in .knot.edn is honored before the two default locations
  done: false
- title: prime --json carries skill_installed and skill_dir; json-protocol topic documents both fields
  done: false
deps:
- kno-01m21aczyqmx
- kno-01m21ad02qt0
---

## Description

Both `prime` preambles (`hitl` and `afk`) end today with a fixed "invoke the `knot` skill" sentence. Make it live: `prime` checks for a `SKILL.md` at `:skill-dir` (when set), then `<project-root>/.claude/skills/knot`, then `~/.claude/skills/knot`. Found → keep today's sentence. Not found → "run `knot help topics`" for the same list of judgment areas, plus one line nudging `knot skill install`. The two preambles' closing sentences must keep their existing disjoint-from-skill-description branch lists (ADR 0017 consequence). `--json` exposes `skill_installed` (boolean) and `skill_dir` (string or null) so an orchestrator can act on it.
