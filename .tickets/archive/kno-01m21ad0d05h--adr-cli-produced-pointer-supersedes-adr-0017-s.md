---
id: kno-01m21ad0d05h
title: 'ADR: CLI-produced pointer supersedes ADR 0017''s generated-pull definition; sync AGENTS.md, README, CHANGELOG'
status: closed
type: chore
priority: 2
mode: hitl
created: '2026-09-08T20:12:25.305570929Z'
updated: '2026-09-09T23:19:21.103690094Z'
closed: '2026-09-09T23:19:21.103690094Z'
parent: kno-01m21abe2vqs
tags:
- docs
- adr
acceptance:
- title: docs/adr/0019 exists in the project's ADR format and 0017 links to it as superseding in part
  done: true
- title: AGENTS.md pointer clause names resources/knot/skill/ as the edit location
  done: true
- title: README install section and AI-agent integration section describe the command and the topics; CHANGELOG has the entry
  done: true
deps:
- kno-01m21aczttph
- kno-01m21aczyqmx
- kno-01m21ad02qt0
- kno-01m21ad077j1
external_refs:
- git:e3649e3
---

## Description

Write `docs/adr/0019-*.md`. It supersedes two parts of ADR 0017: pull is redefined from "generated from the command registry" to "fetched on demand from the installed CLI, versioned with it" (timing, not provenance, is what distinguishes the surfaces), and the rejected option "generate the skill body from the registry" is replaced by "the CLI ships the hand-written skill and installs it". Record the problem (hand-copied skill drifts; skill-less projects can't reach the judgment), the considered options (per-command `--ai` flag — rejected, judgment is cross-cutting and `:notes` already owns per-command caveats; thin skill pointing at the CLI — rejected, taxes every skill fire with a tool call; keep both hand-synced — already rejected in 0017), the prior art (beads `bd prime`/`bd setup`, git guides, `gh help <topic>`, `jj help -k`), and the consequences (single source under resources/, identity test, `:skill-dir`, `prime` live pointer). Add a "superseded in part by 0019" line to 0017. "Topic" is documentation vocabulary and stays in the ADR, not CONTEXT.md (same treatment as pull/push/pointer, see CONTEXT.md flagged ambiguities).

AGENTS.md: the three-surfaces hard rule keeps its shape; the pointer clause now says the skill is produced by the CLI from `resources/knot/skill/`, so editing the pointer means editing there and regenerating the committed copy. README: "AI-agent integration" and "Skill" sections describe `knot help topics` and `knot skill install`. CHANGELOG entry under Unreleased.

## Notes

**2026-09-09T21:23:27.515061403Z**

From kno-01m21ad077j1: ADR 0017's 'prime-skill-pointer is deleted, sentences inlined' clause is superseded — output/prime-pointer builds the closing from two named branch constants (hitl/afk), with per-mode disjointness tests as the drift guard. Also: prime --json's skill_dir (resolved found dir) shares a key name with info --json's paths.skill_dir (config echo); json.md's path table now distinguishes them, and the ADR/README sync should keep that distinction.

**2026-09-09T23:19:21.103690094Z**

ADR 0019 records the CLI-produced pointer. Pull is redefined by timing rather than provenance: what the agent fetches on demand from the installed CLI, versioned with it. 0017's 'generated from the command registry' held only for the command pages, and knot help <topic> prints six hand-written guides off the classpath. 0017's rejected 'generate the skill body from the registry' is replaced by 'the CLI ships the hand-written skill and installs it' — the rejection itself stands, since nothing is generated. Its 'prime-skill-pointer is deleted' consequence is reversed: the closing is composed again by output/prime-pointer over two named per-mode branch constants, guarded by the disjointness test rather than by the helper's absence. Its pointer rule is clarified, not superseded — 'nothing another surface already states' counts authored copies, not served bytes, which is what lets one text sit on pull and pointer at once. Options recorded and rejected: a per-command --ai flag, a thin skill that shells out to knot help, hand-syncing the two copies, install writing :skill-dir into .knot.edn, and install clearing the target directory. Consequences cover the resources/ single source and the stamp-stripped identity test, the skill_dir collision between prime --json (resolved) and info --json (config echo), and kno-01m21ad0gzs4 owning stale-install detection. Markers on the four affected clauses of 0017 follow the idiom 0003 uses for 0009. Surfaces moved with it: AGENTS.md's pointer clause names resources/knot/skill/ and knot skill install; README's install and AI-agent sections cover knot help topics and stop calling the skill repo-bundled; CONTEXT.md's flagged-ambiguities entry points at both ADRs and notes 'topic' lives in 0019; CHANGELOG's Unreleased section covers the four epic commits and the jolt 0.8.5 build fix. 510 tests / 5995 assertions, 0 failures. clj-kondo 0/0.
