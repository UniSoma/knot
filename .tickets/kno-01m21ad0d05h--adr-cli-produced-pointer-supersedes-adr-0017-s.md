---
id: kno-01m21ad0d05h
title: 'ADR: CLI-produced pointer supersedes ADR 0017''s generated-pull definition; sync AGENTS.md, README, CHANGELOG'
status: open
type: chore
priority: 2
mode: hitl
created: '2026-09-08T20:12:25.305570929Z'
updated: '2026-09-08T20:12:25.305570929Z'
parent: kno-01m21abe2vqs
tags:
- docs
- adr
acceptance:
- title: docs/adr/0019 exists in the project's ADR format and 0017 links to it as superseding in part
  done: false
- title: AGENTS.md pointer clause names resources/knot/skill/ as the edit location
  done: false
- title: README install section and AI-agent integration section describe the command and the topics; CHANGELOG has the entry
  done: false
deps:
- kno-01m21aczttph
- kno-01m21aczyqmx
- kno-01m21ad02qt0
- kno-01m21ad077j1
---

## Description

Write `docs/adr/0019-*.md`. It supersedes two parts of ADR 0017: pull is redefined from "generated from the command registry" to "fetched on demand from the installed CLI, versioned with it" (timing, not provenance, is what distinguishes the surfaces), and the rejected option "generate the skill body from the registry" is replaced by "the CLI ships the hand-written skill and installs it". Record the problem (hand-copied skill drifts; skill-less projects can't reach the judgment), the considered options (per-command `--ai` flag — rejected, judgment is cross-cutting and `:notes` already owns per-command caveats; thin skill pointing at the CLI — rejected, taxes every skill fire with a tool call; keep both hand-synced — already rejected in 0017), the prior art (beads `bd prime`/`bd setup`, git guides, `gh help <topic>`, `jj help -k`), and the consequences (single source under resources/, identity test, `:skill-dir`, `prime` live pointer). Add a "superseded in part by 0019" line to 0017. "Topic" is documentation vocabulary and stays in the ADR, not CONTEXT.md (same treatment as pull/push/pointer, see CONTEXT.md flagged ambiguities).

AGENTS.md: the three-surfaces hard rule keeps its shape; the pointer clause now says the skill is produced by the CLI from `resources/knot/skill/`, so editing the pointer means editing there and regenerating the committed copy. README: "AI-agent integration" and "Skill" sections describe `knot help topics` and `knot skill install`. CHANGELOG entry under Unreleased.
