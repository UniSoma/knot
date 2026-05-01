---
id: kno-01kqgqcqmy19
title: New knot update command for non-interactive ticket writes
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-01T02:55:24.318439621Z'
updated: '2026-05-01T02:57:44.436191239Z'
parent: kno-01kqfaqw9bfk
tags:
- v0.3
- cli
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqbjg012
---

## Description

Add `knot update <id>` as a sibling write command. `knot edit` keeps its single meaning ("open in `\$EDITOR`"); `update` is the non-interactive path agents and scripts use.

Resolves the parent ticket kno-01kqfaqw9bfk (expand `edit` vs add `update`?) in favor of the `update` direction.

Flag set:

Frontmatter:
- `--title <text>`
- `--tags <comma-list>`
- `--priority <0..4>`
- `--type <value>`
- `--mode <value>`
- `--assignee <handle>`
- `--parent <id>`
- `--external-ref <ref>` (repeatable)

Body (whole or sectional):
- `--description <text>` — replace `## Description` section
- `--design <text>` — replace `## Design` section
- `--acceptance <text>` — replace `## Acceptance Criteria` section (note: kno-? Q6 promotes ACs to frontmatter; this flag becomes the structured-AC list editor at that point)
- `--body <text>` — replace the whole body. Destructive; **no `--force` ceremony** — git is the documented undo path.

`--json` returns the touched ticket via the envelope from kno-01kqgq9vhmvr (data = post-update ticket).

`add-note` stays distinct and append-only — `update` is purely set/replace, not append. Do not add `--note` to `update`.

`:updated` frontmatter timestamp bumps on every successful write (re-uses `store/save!`).

## Acceptance Criteria

- [ ] `knot update <id>` command implemented
- [ ] Full flag set: `--title --tags --priority --type --mode --assignee --parent --external-ref --description --design --acceptance --body`
- [ ] `--body` works without `--force`; CHANGELOG flags it as destructive
- [ ] `--json` returns the post-update ticket via envelope
- [ ] `:updated` bumps on every write
- [ ] No `--note` flag (append remains `add-note`'s job)
- [ ] Tests cover frontmatter-only update, sectional body update, full-body replace, `--json` mode
- [ ] Help text + README updated; AFK-agent path documented
