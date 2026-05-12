---
id: kno-01kreh4yap1c
title: 'knot.el slice 4: update transient for atomic frontmatter mutations'
status: in_progress
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:30.508447363Z'
updated: '2026-05-12T19:31:33.541488716Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: Pressing , in a show buffer opens a transient with infix args for status, priority, mode, type, tags, assignee, parent
  done: true
- title: Each infix completion reads from knot-info-current's allowed_values / priority_range / defaults
  done: true
- title: Each commit is exactly one knot update --flag value subprocess (atomic — no batching)
  done: true
- title: After a successful update, the show buffer auto-refreshes from knot show --json
  done: true
- title: ok:false envelopes raise user-error in the minibuffer with the envelope's error message; the buffer state is unchanged on failure
  done: true
deps:
- kno-01kreh4n6ryc
---

## Description

Add the update transient invoked with , in a show buffer. The transient exposes infix args for status, priority, mode, type, tags, assignee, and parent. Each set commits as a single `knot update --flag value` call — no buffer pop — and auto-refreshes the show buffer.

Completion sources come from the cached `knot info` envelope (`allowed_values.statuses`, `types`, `modes`, `priority_range`). Mutating from show refreshes the show buffer immediately; cross-buffer refresh (refreshing the visible list buffer too) lands in slice 8.

Modules introduced: `knot-update` (shallow — per-field transients).

See docs/prd/knot-el.md user story 22, 'Editing model', and 'Refresh model' for the design.

## Notes

**2026-05-12T19:09:11.760740941Z**

Slice 4 implementation landed in emacs/knot.el under '## Update transient (knot-update module)'.

Surface:
- ',' in a knot-show-mode buffer launches knot-update-from-show (transient).
- Suffixes: s/status, p/priority, m/mode, t/type, T/tags, a/assignee, P/parent.
- Each suffix runs an interactive knot-update-set-* command that:
  1. Resolves the ticket id via knot-update--ticket-id (errors when not in knot-show-mode).
  2. Reads the new value with completing-read (status/mode/type/priority) or read-string (tags/assignee/parent).
  3. Calls knot-cli-call (list "update" id flag value) — exactly one subprocess per commit, no batching.
  4. Calls knot-show--refresh on success.
- Completion sources: knot-info-allowed-values for statuses/types/modes; allowed_values.priority_range.min..max for priority; current ticket value as the prompt default.
- AC #5 (ok:false leaves buffer unchanged): knot-cli-call already raises user-error on ok:false, which short-circuits the refresh call. No additional handling needed.

Verification:
- bb lint:elisp: byte-compile clean. Only pre-existing markdown-mode Package-Requires stale-warning at line 7 (unchanged from slice 3).
- bb test: 347 tests, 0 failures.
- emacs --batch smoke test confirms all 7 setters are commandp, the transient is fboundp, and (lookup-key knot-show-mode-map (kbd ",")) resolves to knot-update-from-show.
- PRD says no automated tests in MVP; interactive transient prompts + CLI commit + refresh cycle remain manual-verify.

**2026-05-12T19:31:33.541488716Z**

Follow-up tweak to knot-update-set-parent: parent now uses completing-read over live tickets (id  title candidates), not read-string. C-u prefix arg includes closed tickets in the candidate list. Empty input still clears the parent. Implementation: new knot-update--read-parent helper. knot-update-set-parent is now (interactive "P"). byte-compile clean.
