---
id: issue-0007
title: add-note, knot edit, and close --summary
status: done
type: afk
blocked_by:
  - issue-0003
parent: docs/prd/knot-v0.md
created: 2026-04-28
completed: 2026-04-28
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

- [x] `knot add-note <id> "text"` appends a `**<ISO timestamp>**\n\n<body>` block under `## Notes`
- [x] If `## Notes` is missing, it is created at the end of the body
- [x] Layered input: explicit text arg wins; if absent and stdin is not a TTY, read stdin; otherwise open the editor with a temp file containing a `# Lines starting with '#' will be ignored.` header and a context line
- [x] Empty content cancels with no file change
- [x] Editor resolution: `$VISUAL → $EDITOR → nano → vi`
- [x] `knot edit <id>` opens the ticket file in the resolved editor; never renames the file (slug stability per slice 1)
- [x] After `knot edit` returns, the file is reloaded and re-saved through `knot.store/save!` so `:updated` bumps even on no-op edits (acceptable per PRD; hash-and-skip parked)
- [x] `knot close <id> --summary "text"` appends a closure note via the same writer path as `add-note`, then performs the close transition
- [x] `knot status <id> <terminal-status> --summary "text"` works the same way
- [x] `--summary` errors at command start when the new status is non-terminal (start, reopen, or any non-terminal `status X` transition)
- [x] `--summary` accepts a string value only — no stdin, no editor
- [x] Empty `--summary ""` is a no-op for the note (close still happens)
- [x] Notes appended via `--summary` remain on `reopen` (historical journal)
- [x] Tests: note format/anchor handling, layered input modes, editor resolution order, `--summary` on terminal vs non-terminal, empty-summary no-op, summary persistence across reopen

## Blocked by

- issue-0003 (`issues/0003-lifecycle-transitions-and-archive.md`)

## Implementation notes

### Single-save semantics for `close --summary` / `status X <terminal> --summary`

When `--summary` is supplied, the status mutation, the appended `## Notes` block, the `:updated`/`:closed` stamping, and the live→archive move all happen in one `knot.store/save!`. There is no intermediate two-write state on disk: a save failure means neither the status transition nor the summary lands. The summary is folded into the in-memory ticket's `:body` via `ticket/append-note` before save, so it travels through the same writer path as `add-note-cmd`.

### `:summary` validation triggers on key presence, not value

`status-cmd` checks `(some? summary)` to decide whether to enforce the terminal-status rule, then `(seq summary)` to decide whether to actually append a note. The two predicates split the two AC requirements cleanly:

- `--summary ""` on a non-terminal target → still errors (key is present, target is non-terminal).
- `--summary ""` on a terminal target → close happens, no note appended (empty body skips the writer).
- `--summary "text"` on a non-terminal target → errors before any file is touched.

`start-cmd` and `reopen-cmd` are sugar over `status-cmd` and propagate `opts` verbatim, so the validation lives in one place.

### Layered input is injected, not magical

`add-note-cmd` does not probe `*in*` or `System/console` itself. It receives `:text`, `:stdin-tty?`, `:stdin-reader-fn`, and `:editor-fn` and applies the layering policy purely. `knot.main` wires the real implementations (probe `(System/console)`, `slurp *in*`, spawn-editor-on-temp-file). Tests inject deterministic fakes for each branch — that is what made the layered-input AC testable end-to-end without shelling out.

### TTY detection uses `(System/console)`

`(System/console)` returns nil when *either* stdin or stdout is redirected. For the common shapes this is the right answer:

- piped stdin (`echo x | knot add-note id`) → nil → reads stdin ✓
- pure interactive terminal → non-nil → opens editor ✓
- both redirected (e.g. wired into a pipeline) → nil → reads stdin ✓

The unusual `stdout-redirected-but-stdin-TTY` shape (`knot add-note id > out.txt`) treats stdin as non-TTY and slurps it, which is normally empty and cancels. Acceptable in v0; a precise stdin-isatty check would need JNI or a shell-out and is parked.

### `edit-cmd` always re-saves

After the editor returns, `edit-cmd` reloads the file and re-saves it through `knot.store/save!` unconditionally. Two upsides: (1) `:updated` bumps even on no-op edits (hash-and-skip parked per PRD); (2) any in-editor status change automatically triggers archive routing through the existing `save!` self-healing path. The slug suffix is preserved by passing `nil` for `slug` to `save!`, reusing the slug-recovery branch that the lifecycle commands rely on — so editing the title in-body never renames the file.

### Editor temp-file convention for `add-note`

The editor opens a fresh temp file pre-filled with `# Lines starting with '#' will be ignored.\n# <ctx-line>\n\n`. After the editor exits, lines beginning with `#` are stripped before the content is treated as the note. Git-commit-style. The temp file is deleted in a `finally` regardless of editor exit. The "blank content cancels" rule in `add-note-cmd` means a user who quits the editor without removing the comment header (or saves an empty file) gets a clean cancel.

### `knot.store/find-existing-path` is now public

Was private; exposed because `knot.main`'s `add-note-handler` needs to disambiguate two cases when `cli/add-note-cmd` returns nil — "id does not resolve" (exit 1, stderr message) vs "content was blank, cancel was the intent" (exit 0, silent). The cleanest disambiguator is to re-probe the id after the cmd returns nil.

### Test helper change

`run-knot` in `integration_test.clj` now passes `:in ""` to `babashka.process/process`. Without it, subprocesses inherit the parent test runner's stdin; commands that read stdin on the non-TTY branch (notably `add-note` with no text arg) would block waiting for EOF. This affects every integration test that exercises the new commands and is the recommended default for any future test that runs the CLI as a subprocess.
