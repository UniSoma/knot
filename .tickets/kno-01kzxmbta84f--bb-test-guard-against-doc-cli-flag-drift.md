---
id: kno-01kzxmbta84f
title: bb test guard against doc/CLI flag drift
status: open
type: chore
priority: 3
mode: afk
created: '2026-08-13T13:18:10.746284458Z'
updated: '2026-08-13T13:18:10.746284458Z'
tags:
- tooling
- testing
- docs
acceptance:
- title: A test extracts flags from knot invocations across the skill, docs/agents, AGENTS.md, and README.md
  done: false
- title: Each extracted flag is checked against the accepting command's knot help output, not merely against the global flag set
  done: false
- title: Non-knot flags in the same files (--lint, --timeout, --discover-ports) do not trip the check
  done: false
- title: bb test passes on the current tree and fails when a knot flag in any doc is renamed or removed
  done: false
deps:
- kno-01kzxmbd81de
---

## Description

## Description

The bundled skill at `.claude/skills/knot/` is copied into every project that
uses knot, so a flag rename here silently misleads every downstream agent
until someone notices by hand. Two live drifts (`--acceptance-complete`
missing from the skill entirely, `create --title` in the agent docs) were
found only because the skill was audited manually this session.

Add a test to `bb test` that keeps documentation honest about flag names.

## Design

Extract flag tokens from `knot <cmd> ...` invocations in:

- `.claude/skills/knot/**/*.md`
- `docs/agents/*.md`
- `AGENTS.md`, `README.md`

and assert each is accepted by the command it is written against, per
`knot help <cmd>`.

Two constraints learned from running this by hand, worth encoding rather than
rediscovering:

- **Scope extraction to knot invocations.** The same files carry `--lint`
  (clj-kondo), `--timeout`, and `--discover-ports` (nREPL). A naive
  "every `--flag` in the file" sweep reports all of them.
- **Resolve against the specific command, not the global flag set.** A
  global name-existence check would have passed `knot create --title`,
  because `--title` is a real flag — on `update`. Per-command resolution is
  what makes the check catch the drift that actually shipped.

Markdown table separators (`---`, `-----`) tokenize as flags under a loose
regex; the extractor needs to reject them.

This ticket depends on the doc-correction ticket: landing the guard first
would put `bb test` red.
