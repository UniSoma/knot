---
id: kno-01m21ad077j1
title: 'knot prime: live pointer sentence depending on whether the skill is installed'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-09-08T20:12:25.156663522Z'
updated: '2026-09-09T21:22:44.208861264Z'
closed: '2026-09-09T21:22:44.208861264Z'
parent: kno-01m21abe2vqs
tags:
- cli
- agents
acceptance:
- title: With a skill at any of the three locations, both preambles end with the invoke-the-skill sentence; without one, they end with the knot help topics sentence and a knot skill install nudge
  done: true
- title: :skill-dir in .knot.edn is honored before the two default locations
  done: true
- title: prime --json carries skill_installed and skill_dir; json-protocol topic documents both fields
  done: true
deps:
- kno-01m21aczyqmx
- kno-01m21ad02qt0
---

## Description

Both `prime` preambles (`hitl` and `afk`) end today with a fixed "invoke the `knot` skill" sentence. Make it live: `prime` checks for a `SKILL.md` at `:skill-dir` (when set), then `<project-root>/.claude/skills/knot`, then `~/.claude/skills/knot`. Found → keep today's sentence. Not found → "run `knot help topics`" for the same list of judgment areas, plus one line nudging `knot skill install`. The two preambles' closing sentences must keep their existing disjoint-from-skill-description branch lists (ADR 0017 consequence). `--json` exposes `skill_installed` (boolean) and `skill_dir` (string or null) so an orchestrator can act on it.

## Notes

**2026-09-09T21:22:44.208861264Z**

prime's closing pointer is now live: it searches :skill-dir, then <project-root>/.claude/skills/knot, then ~/.claude/skills/knot for a SKILL.md. Found → today's 'invoke the knot skill' sentence; not found → the same branch list routed to 'knot help topics' plus a 'knot skill install' nudge. Both preambles keep their own branch list (hitl names autonomous mode, afk doesn't), still disjoint from the skill description's cold triggers.

--json carries skill_installed and skill_dir. skill_dir is the *resolved* directory whose SKILL.md answered the search, not the configured :skill-dir that info --json echoes under the same key — the json topic's path table now spells out that difference. Unixified like every other envelope path, matching skill install --json's dir.

installed-skill-dir takes an injectable :home so tests never reach the developer's real home; prime-ctx in cli_test pins it into the fixture. Any future subprocess assertion on preamble text must do the same.

Surfaces moved in the same commit: README's prime --json key list, the prime help :notes caveat, and both copies of references/json.md.
