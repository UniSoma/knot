---
id: kno-01m21abe2vqs
title: 'Ship the knot skill from the CLI: help topics, skill install, live prime pointer'
status: open
type: epic
priority: 2
mode: hitl
created: '2026-09-08T20:11:33.851882355Z'
updated: '2026-09-08T20:12:25.609950598Z'
tags:
- docs
- cli
- agents
links:
- kno-01m21ad0gzs4
---

## Description

Umbrella for making the CLI the single source of the agent-facing judgment that today lives only in the hand-copied `.claude/skills/knot/` skill.

### Problem

- The skill is installed by `cp -r` from a checkout (README), so every client project holds a snapshot that drifts silently from the installed `knot` version. Two drift-guard tests (`doc_flags_test`, `doc_codes_test`) exist only to fight this inside the repo; nothing protects a client copy.
- A project with the CLI and the `SessionStart` hook but no skill cannot reach the judgment at all — `prime`'s pointer sentence says "invoke the `knot` skill" into a void.
- Non-Claude agents (`agents/openai.yaml` exists) get the same material only if someone copies it for them.

### Design (grill session 2026-09-08)

- Six noun-named concept guides bundled under `resources/`, printed by `knot help <topic>`, listed by `knot help topics`, advertised by one line in `knot --help`. Commands, aliases and subcommands win over topics in the `help` dispatcher; a test forbids a topic named like any of them. Topics: `intro`, `lifecycle`, `graph`, `json`, `autonomous`, `writes`.
- The same markdown *is* the skill: `intro` is the SKILL.md body, the other five are its `references/`. `knot skill install [dir]` writes it out, always overwriting, with an HTML-comment version stamp after the frontmatter. Default dir is `.knot.edn` `:skill-dir` when set, else `<project-root>/.claude/skills/knot`. `agents/openai.yaml` ships alongside.
- This repo keeps a committed copy under `.claude/skills/knot/` guarded by a byte-identity test against `resources/`; the flag/code drift tests repoint at `resources/`.
- `knot prime` checks for an installed skill (`:skill-dir`, project default, `~/.claude/skills/knot`) and closes with "invoke the `knot` skill" when found, or "run `knot help topics`" plus a `knot skill install` nudge when not.
- `knot init` unchanged except a `:notes` mention of `knot skill install`.

### Prior art

beads (`bd prime` = live state from the binary, `bd setup <agent>` = install matching-version instruction files); git guides (`git help -g`, noun-named `gitrevisions`/`gitglossary`); `gh help environment`; `jj help -k`. No shipped first-party `--ai`/`--llm` help flag was found — a per-command flag was rejected because the judgment is cross-cutting and per-command caveats already have `:notes`.

### Records

A new ADR supersedes ADR 0017's "pull is generated from the registry" definition and its rejection of a CLI-produced pointer. AGENTS.md surface rule, README install section and CHANGELOG move in the same commit as the code. "Topic" stays documentation vocabulary in the ADR, not a CONTEXT.md term.

Follow-up (separate hitl ticket): stale-skill check in `knot check` / `prime` off the version stamp.
