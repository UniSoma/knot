---
id: kno-01kznwnkqfav
title: 'knot.el: render show-buffer timestamps in local timezone'
status: closed
type: task
priority: 3
mode: hitl
created: '2026-08-10T13:09:24.847091550Z'
updated: '2026-08-10T13:14:11.744093961Z'
closed: '2026-08-10T13:14:11.744093961Z'
---

## Description

Show buffer renders created/updated fields and body note headers (strict ^\*\*<instant>\*\*$ lines) in the local zone via a new knot-show--format-instant helper (format-time-string, minute precision, %z offset as-is; unparseable input returned verbatim). Display-only: CLI, ticket files, and capture buffers stay UTC. Accepted asymmetry: edit-body capture still shows UTC because it commits its text back verbatim. Docs: two sentences in emacs/README.md. No SKILL/CONTEXT/ADR change. Verify with bb lint:elisp + manual check in live Emacs.

## Notes

**2026-08-10T13:14:06.204539050Z**

Implemented: knot-show--format-instant (save-match-data-wrapped parse + format-time-string "%Y-%m-%d %H:%M %z", returns input verbatim on nil/unparseable), knot-show--note-header-re (strict whole-line bold-delimited), knot-show--localize-body-notes. Wired into the created/updated field renders and a single pass over body before insert. knot-show--field-prefill docstring records the read/write asymmetry. emacs/README.md gained a Timestamps section.

Gotcha found during verification: parse-iso8601-time-string calls string-match internally and clobbered the match data replace-regexp-in-string uses to advance, which duplicated every note header. Fixed with save-match-data.

Verified: batch-Emacs render of a real ticket envelope (fields localized; inline prose instants and fenced JSON untouched), half-hour zone (Asia/Kolkata -> +0530), bb lint:elisp clean, clj-kondo clean, bb test 445/445.

**2026-08-10T13:14:11.744093961Z**

Show buffer renders created/updated and body note headers in the local zone via knot-show--format-instant (minute precision, explicit %z offset, verbatim passthrough on unparseable input). Body pass uses a strict whole-line bold-delimited pattern, so quoted instants in prose/code fences are untouched. Display-only: CLI, ticket files, and capture buffers stay UTC; the read/write asymmetry is documented in emacs/README.md and the knot-show--field-prefill docstring. Manual eyeball in a live Emacs still pending on the user.
