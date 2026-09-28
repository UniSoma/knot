---
id: kno-01m3mr0atq0h
title: Emit a JSON error envelope when a required positional argument is missing
status: closed
type: feature
priority: 3
mode: afk
created: '2026-09-28T19:31:26.167245448Z'
updated: '2026-09-28T21:47:18.347363553Z'
closed: '2026-09-28T21:47:18.347363553Z'
tags:
- cli
- json
- settled
acceptance:
- title: Every command whose handler checks for a missing positional emits an error envelope under --json
  done: true
- title: Text-mode output and exit code for the missing-argument case are unchanged
  done: true
- title: The envelope's code is invalid_argument, and json.md's CLI-usage paragraph no longer lists a missing positional as staying outside the envelope
  done: true
- title: knot skill install regenerates .claude/skills/knot/ and bb test passes against the source
  done: true
---

## Description

Under `--json`, a command run without a required positional argument prints `{ok: false, error: {code: "invalid_argument", message}}` on stdout and exits 1, the same way other `--json` refusals do. Today it prints a plain-text line on stderr and writes no envelope, so a caller that parses stdout gets nothing.

This covers every `--json`-capable command that requires a positional: the ticket title on `create`, the id(s) on the ticket commands, and the ticket id or selector on the `document` subcommands. Without `--json`, the stderr message and exit code do not change. Other argument-parse failures (unknown flag, out-of-range numeric, flag values such as `list --component`) are out of scope and stay on stderr.

`references/json.md` changes in the same commit. Its CLI-usage paragraph stops listing a missing positional among the failures that stay outside the envelope, and the `invalid_argument` row covers the new case without listing the commands one by one.

### Decisions

- Missing positionals reuse `invalid_argument`. No new error code.
- Only the missing-positional case moves. Unknown-flag and out-of-range numeric errors stay on stderr under `--json`.
- One private helper beside `die` in `main.clj`. It takes `json?` and the message, emits the envelope under `--json`, and otherwise calls `die` with the existing prefixed text. Every missing-positional site calls it.
- The envelope `message` drops the `knot <cmd>: ` prefix (for example `an id is required`), like every other envelope message. The envelope has no extra field.
- Exit code stays 1 in both modes.
- `edit` (no `--json`), the id checks on `list --component`/`--closure` (flag values) and the bare `document`/`skill` group help are excluded.
- Tests go in `integration_test.clj`, beside the existing missing-`--title` document cases. Each handler family gets one `--json` case, and one text-mode case checks the stderr line is unchanged.

## Notes

**2026-09-28T21:47:18.347363553Z**

A missing required positional under --json now emits {ok:false, error:{code:"invalid_argument", message}} on stdout (exit 1) for create, show, the status transitions, dep/undep, dep tree, link, unlink, delete, add-note, update and all five document subcommands, via one die-missing-arg helper in main.clj. Text mode keeps its prefixed stderr line. json.md's invalid_argument row and CLI-usage paragraph updated; skill copy regenerated.
