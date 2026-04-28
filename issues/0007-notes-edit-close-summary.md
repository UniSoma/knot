---
id: issue-0007
title: add-note, knot edit, and close --summary
status: ready
type: afk
blocked_by:
  - issue-0003
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0007-notes-edit-close-summary.md
---

# add-note, knot edit, and close --summary

## Parent document

`docs/prd/knot-v0.md`

## What to build

The annotation surface: `knot add-note <id>` for appending timestamped notes (via arg, stdin, or editor), `knot edit <id>` for opening a ticket in `$VISUAL`/`$EDITOR`, and `--summary "text"` on `close` and `status X <terminal>` for closing with a closure note in one command. All three share a single note-append code path so timestamp format and `## Notes` anchor handling are consistent.

## User stories covered

- 4 (`knot edit <id>` opens the ticket in `$EDITOR`)
- 17 (`knot add-note <id> "text"` appends a timestamped note)
- 18 (`knot add-note` reads from stdin if piped or opens `$EDITOR` if interactive)
- 49 (`knot close <id> --summary "text"` closes and appends a closure note in one command)

## Acceptance criteria

- [ ] `knot add-note <id> "text"` appends a `**<ISO timestamp>**\n\n<body>` block under `## Notes`
- [ ] If `## Notes` is missing, it is created at the end of the body
- [ ] Layered input: explicit text arg wins; if absent and stdin is not a TTY, read stdin; otherwise open the editor with a temp file containing a `# Lines starting with '#' will be ignored.` header and a context line
- [ ] Empty content cancels with no file change
- [ ] Editor resolution: `$VISUAL → $EDITOR → nano → vi`
- [ ] `knot edit <id>` opens the ticket file in the resolved editor; never renames the file (slug stability per slice 1)
- [ ] After `knot edit` returns, the file is reloaded and re-saved through `knot.store/save!` so `:updated` bumps even on no-op edits (acceptable per PRD; hash-and-skip parked)
- [ ] `knot close <id> --summary "text"` appends a closure note via the same writer path as `add-note`, then performs the close transition
- [ ] `knot status <id> <terminal-status> --summary "text"` works the same way
- [ ] `--summary` errors at command start when the new status is non-terminal (start, reopen, or any non-terminal `status X` transition)
- [ ] `--summary` accepts a string value only — no stdin, no editor
- [ ] Empty `--summary ""` is a no-op for the note (close still happens)
- [ ] Notes appended via `--summary` remain on `reopen` (historical journal)
- [ ] Tests: note format/anchor handling, layered input modes, editor resolution order, `--summary` on terminal vs non-terminal, empty-summary no-op, summary persistence across reopen

## Blocked by

- issue-0003 (`issues/0003-lifecycle-transitions-and-archive.md`)
