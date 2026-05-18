---
id: kno-01krsytqv33a
title: Add --show flag on write commands (create/update/add-note/status/close/dep) to skip the verify round-trip
status: open
type: feature
priority: 1
mode: hitl
created: '2026-05-17T03:14:15.000968825Z'
updated: '2026-05-18T17:04:05.648411451Z'
tags:
- triage
links:
- kno-01kr0129m0y9
---

## Description

Today every write command emits just the saved path (or a small JSON envelope). Both humans and agents routinely follow up with `knot show <id>` to verify what landed in frontmatter — especially after `create`, where type/priority/mode/tags/acceptance/links were all set in a single call and the agent or user wants a quick read-back. A `--show` flag would collapse the round-trip into one invocation, which is cheap for humans and meaningful for agent token cost (knot is dogfooded by agents in this repo).

## Design

**Deferred — this ticket is at triage.** Open design questions to resolve before implementation:

1. **Scope.** Just `create`, or uniformly across every write command (`create`, `update`, `add-note`, `status`, `close`, `dep`, `update --add-tag`/etc)? Uniform is more useful but a wider surface and a longer test matrix.

2. **`--show` × `--json` interaction.** Today `--json` on a write command returns a write-result envelope (path, id, etc). With `--show`, does `--json` switch to the `knot show --json` envelope (the full ticket)? Or do we keep the write envelope and append the rendered show output as a sibling field? The first is simpler; the second preserves CLI-pipeline ergonomics.

3. **Stdout contract.** Today `knot create` prints the path on stdout, suitable for `$(knot create …)` in shells. `--show` would change that. Acceptable? Or should `--show` go to stderr so the path stays on stdout? (Probably not — but worth flagging.)

4. **Default behaviour.** Should `--show` ever become the default with a `--quiet` opt-out, or always remain opt-in? Defaults bias agent workflows; opt-in keeps shell scripts stable.

5. **Skill update.** `.claude/skills/knot/SKILL.md` should advertise `--show` so agents stop chaining `knot create … && knot show <id>` (the pattern that motivated this ticket).
