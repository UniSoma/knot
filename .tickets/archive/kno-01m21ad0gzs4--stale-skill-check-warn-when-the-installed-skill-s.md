---
id: kno-01m21ad0gzs4
title: 'Stale-skill check: warn when the installed skill''s version stamp lags the CLI'
status: closed
type: feature
priority: 3
mode: hitl
created: '2026-09-08T20:12:25.503872895Z'
updated: '2026-09-14T16:05:38.931262420Z'
closed: '2026-09-14T16:05:38.931262420Z'
tags:
- cli
- agents
links:
- kno-01m21abe2vqs
acceptance:
- title: knot check reports skill_stale (warning) for a project skill copy with an older, newer, missing or unreadable stamp, and nothing when the stamp matches or no project copy exists
  done: true
- title: knot prime text and JSON (skill_stale, skill_version) flag a stale copy wherever it resolved, with location-specific fix wording
  done: true
- title: check --help notes, skill references/json.md (reinstalled copy), CHANGELOG and ADR 0019 updated in the same commit
  done: true
---

## Description

Follow-up to the skill-from-CLI umbrella kno-01m21abe2vqs (linked, not a child). `knot skill install` already stamps `<!-- installed by knot <version> -->` into SKILL.md; nothing reads it back. ADR 0019 assigns detecting a lagging install to that stamp and to this ticket.

A **stale skill** is an installed SKILL.md whose stamp is missing, unreadable, or differs from the running CLI version, in either direction. The warning targets the repository: the fix is `knot skill install` and commit. It warns and never fails.

## Design

### Detection

One function, shared by both surfaces. Plain version-string inequality, no content hash. A missing stamp and an unparseable stamp both count as stale.

### knot check

- Checks the project copy only (`:skill-dir`, then `<project>/.claude/skills/knot`), never `~/.claude/skills/knot`. No skill installed: no issue.
- Issue: `severity: warning`, `code: skill_stale`, `ids: []`, `path`: the SKILL.md, `value`: stamp version or `null`. The message distinguishes older, newer and missing/unreadable, and ends with "run `knot skill install` and commit".
- Global check like cycles and `active_status`: positional ids do not skip it. `--code` and `--severity` filter it as usual. Warnings exit 0.

### knot prime

- Checks whichever copy `installed-skill-dir` resolves, home copy included.
- Text: keep "invoke the `knot` skill", add one line under it naming the stamp and CLI versions. Fix wording depends on location: project copy "run `knot skill install` and commit"; home copy "run `knot skill install ~/.claude/skills/knot`".
- JSON: add `skill_stale` (boolean) and `skill_version` (string or null) beside `skill_installed` and `skill_dir`.

### Same commit

- `check --help` `:notes` entry for `skill_stale`.
- `resources/knot/skill/references/json.md`, then `knot skill install` to regenerate the committed copy.
- CHANGELOG entry.
- ADR 0019: define "stale skill" next to the stamp sentence and point to `skill_stale`. No new ADR. Nothing goes in CONTEXT.md: like the context surfaces, the stamp is documentation vocabulary, which that file keeps out.

### Tests (write first)

Older, newer, missing and unreadable stamps; home-only install on both surfaces (prime warns, check is silent); no skill installed.

## Notes

**2026-09-14T16:05:38.931262420Z**

Shipped in b9e6357: knot check reports a skill_stale warning for the project skill copy (:skill-dir, else .claude/skills/knot) whose version stamp is missing, unreadable, or differs from the CLI in either direction, never failing and never reading the home copy. knot prime flags whichever copy its pointer found, home included, with a line under the skill pointer naming both versions and location-specific fix wording; prime --json gains skill_stale and skill_version. The stamp pattern accepts pre-release suffixes so a fresh install never warns. check --help notes, json.md (reinstalled copy), ADR 0019 and CHANGELOG updated.
