---
id: kno-01kqe8kjmrpp
title: knot ai setup <agent> [dir] — bootstrap a project for agent-friendly knot use
status: open
type: task
priority: 2
mode: hitl
created: '2026-04-30T03:58:31.064679884Z'
updated: '2026-04-30T03:58:31.064679884Z'
---

## Description

Add a `knot ai setup` subcommand that bootstraps a project so AI coding agents pick up knot conventions automatically, instead of each user wiring it up by hand.

## Shape

```
knot ai setup <agent> [project-dir]
```

Examples:

- `knot ai setup claude` — set up the cwd's project for Claude Code
- `knot ai setup claude ~/code/foo` — same, against a different project root

`<agent>` is an enum (initially `claude`; leave room for `cursor`,
`codex`, etc.). `[project-dir]` defaults to cwd; should resolve to a
project root the same way the rest of knot does (walk up to
`.knot.edn` / `.tickets/`).

## What it does (for `claude`)

1. **SessionStart hook** — register a hook in
   `.claude/settings.json` (or local equivalent) that runs `knot prime`
   on session start so the agent has live ticket context without the
   user having to ask. Idempotent: don't duplicate the entry if it's
   already there; update if the command shape has changed.

2. **Skill** — copy/update the bundled `knot` skill into the project's
   `.claude/skills/` (mirroring how knot prime currently ships it). On
   re-run, replace stale copies so projects stay in sync with the
   installed knot version.

3. **CLAUDE.md / AGENTS.md** — ensure a short, stable "use the knot CLI
   for tickets" entry is present. If the file doesn't exist, create a
   minimal one. If it exists, append/update a marked block (e.g.
   `<!-- knot:start --> ... <!-- knot:end -->`) so re-runs don't
   duplicate or clobber unrelated content.

Print a summary of what was added/updated/skipped.

## Why

Right now we tell users to manually:
- add a SessionStart hook
- copy the skill
- edit CLAUDE.md

That's the same three steps in every project, and it's the kind of
friction that makes people not bother — which means agents don't pick
up knot conventions and we get back to "Claude is hand-editing
.tickets/ files again". A single `knot ai setup claude` solves it.

## Open questions

- Flag for dry-run? (`--dry-run` to print the diff without writing)
- Where does the skill content live in the knot binary/source so we
  can write it out? (Today it's at `.claude/skills/knot/SKILL.md` in
  this repo.)
- Scope of CLAUDE.md edit — minimal one-paragraph entry, or the
  fuller intent-translation table? Probably the short one, with a
  pointer to invoke the knot skill for the full reference.
- User-level vs project-level Claude settings — default to project
  (`.claude/settings.json` at project root)? Add a `--user` flag to
  target `~/.claude/`?
- Hook: `knot prime` blocks on session start; is that fast enough on
  cold cache? Should the hook be `knot prime --json` piped to a
  cheaper rendering, or stay as-is?

## Acceptance

- `knot ai setup claude` in a project with `.knot.edn` does steps 1-3
  and is fully idempotent on re-run.
- Running it in a project without `.knot.edn` errors clearly (or
  prompts to run `knot init` first).
- Subcommand surfaces in `knot --help` and `knot ai --help`.
