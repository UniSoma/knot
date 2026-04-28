# knot

A babashka CLI ticket tracker for solo developers. Tickets are markdown
files with YAML frontmatter under `.tickets/`; closed tickets auto-move
to `.tickets/archive/`. See [`docs/prd/knot-v0.md`](docs/prd/knot-v0.md)
for the full design rationale.

## AI agent context-injection

`knot prime` emits a five-section markdown primer summarizing project
state — preamble, project metadata, in-progress tickets, ready tickets
(capped at 20 by default), and a schema/command cheatsheet — for
injection into a fresh AI agent session.

```text
knot prime                    # five-section markdown primer
knot prime --mode afk         # filter ready section to agent-runnable work
knot prime --limit 5          # override the default ready cap of 20
knot prime --json             # bare object: {project, in_progress, ready, ready_truncated, ready_remaining}
```

`knot prime` always exits 0, including when run from a directory with
no Knot project (the preamble in that case directs the user to `knot
init`), when the project has zero tickets, or when only archived
tickets exist. This is the load-bearing guarantee that makes it safe
to wire into a global session-start hook without conditional
fall-throughs.

### Claude Code `SessionStart` hook

Add the following to `~/.claude/settings.json` (global) or
`<project>/.claude/settings.json` (project-local) so every fresh
Claude Code session starts with the primer in context:

```json
{
  "hooks": {
    "SessionStart": [
      {
        "matcher": "startup",
        "hooks": [
          {
            "type": "command",
            "command": "knot prime"
          }
        ]
      }
    ]
  }
}
```

The hook reads `knot prime`'s stdout and injects it as additional
context for the session — no JSON wrapper required, plain markdown
on stdout reaches the session as context. `knot init` does not modify
`.claude/settings.json`; hook setup is opt-in, never automatic.

For agent-runnable session presets, swap the command for
`knot prime --mode afk` to surface only `mode: afk` ready tickets.
