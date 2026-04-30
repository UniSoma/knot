---
id: kno-01kqfaqw9bfk
title: 'Non-interactive ticket updates: expand ''knot edit'' or add ''knot update''?'
status: open
type: task
priority: 3
mode: hitl
created: '2026-04-30T13:55:03.595115390Z'
updated: '2026-04-30T13:55:03.595115390Z'
---

## Description

Today `knot edit <id>` only opens the file in `$EDITOR`. That blocks scripted/non-interactive updates — e.g. setting a body from a heredoc, retitling, or retagging from a one-liner. An attempt like:

```sh
knot edit <id> --body "$(cat <<'INNER'
... long body ...
INNER
)"
```

silently does nothing useful because `edit` ignores those flags and just launches the editor.

`knot add-note` covers *additive* updates well, but there's no CLI path for *replacing* the title, tags, body, or a single body section. Autonomous agents (no terminal) can't use `edit` at all, so they currently can't revise anything that isn't a note.

## Question

Two shapes seem reasonable — pick one (or argue for a third):

1. **Expand `knot edit`** to accept flags. With no flags it stays interactive (current behavior); with any of the flags below it does a non-interactive write.
2. **Add `knot update`** as a sibling write command, leaving `knot edit` as the interactive-only path.

Candidate flags either way:

- Frontmatter: `--title`, `--tags`, `--assignee`, `--priority`, `--type`, `--mode`, `--parent`, `--external-ref`
- Whole body: `--body` (replace entire markdown body, stdin supported)
- Sections: `--description`, `--design`, `--acceptance` (replace just that H2 section, mirroring `knot create` flags)

## Tradeoffs to weigh

- **One command vs. two**: expanding `edit` keeps the surface small but conflates "open editor" with "patch fields". `update` is more discoverable and matches the read/write split (`show` vs `edit/update`).
- **Symmetry with `create`**: `--description` / `--design` / `--acceptance` already exist on `create`. Mirroring them on the update path is a natural extension regardless of which command hosts them.
- **Agent ergonomics**: AFK agents need *some* non-interactive write path. Today the only one is `add-note`, which is append-only.
- **Safety**: replacing a whole body via `--body` is destructive. Worth considering whether sectional flags should be the default and `--body` requires `--force` or similar.

## Acceptance

- Decision recorded (expand `edit` vs new `update`) with rationale
- Flag set finalized (frontmatter / body / sections)
- Stdin behavior defined for `--body` and section flags
- `:updated` bumped on every successful write, same as other write commands
- Help text + README updated; AFK agents documented as the primary consumer
