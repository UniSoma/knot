---
id: kno-01kve7d1yc4h
title: knot.el — umbrella-only candidates + cursor pre-fill for parent filter
status: closed
type: task
priority: 3
mode: afk
created: '2026-06-18T20:40:16.076615081Z'
updated: '2026-06-18T20:47:31.318733067Z'
closed: '2026-06-18T20:47:31.318733067Z'
acceptance:
- title: The knot-list-filter-set-parent popup lists only umbrella tickets (children_total > 0); knot-update-set-parent and knot-create--read-parent candidate sets are unchanged
  done: true
- title: knot-update--read-parent gains one opt-in optional arg (default nil) that filters candidate rows to children_total > 0 before building choices/annotations
  done: true
- title: When the cursor is on an umbrella row, the popup pre-fills the minibuffer with that ticket's id (plain RET applies it)
  done: true
- title: 'Pre-fill precedence: cursor-umbrella id > active parent-filter value > empty'
  done: true
- title: Cursor-umbrella detection reads the always-on CHLD cell at point (non-dash); no extra CLI call
  done: true
- title: 'With no umbrellas in scope, the popup still opens (non-require-match): raw-id typing and empty-RET-to-clear both work'
  done: true
- title: C-u ,P still widens candidates to include closed/archived umbrellas
  done: true
---

## Description

In `knot-list-mode`, optimize `knot-list-filter-set-parent` (`,P`) for the common "spot an umbrella, filter by it" workflow. The parent-filter popup should offer only umbrella tickets (those with children), and when the cursor sits on an umbrella row, that ticket is pre-filled so `,P RET` applies it immediately.

Scope is the filter command only — assigning a parent (`knot-update-set-parent`) and create-time parent (`knot-create--read-parent`) continue to offer all tickets. An umbrella is any ticket with `children_total > 0` (the same signal the always-on CHLD column uses, including umbrellas whose children are all closed).

## Design

Candidate restriction: `knot-update--read-parent` gains one opt-in optional arg (default nil) that `seq-filter`s rows to `children_total > 0` before building choices/annotations. The filter caller passes it; the other two callers stay unchanged.

Cursor pre-fill is computed entirely in the caller and passed as the existing `current` argument (the helper already pre-fills from `current`). Precedence: cursor-umbrella id > active parent-filter value > empty. The cursor-umbrella id wins over an active filter, so `,P RET` no longer means "keep current filter" when the cursor is on an umbrella — that case is still reachable by editing the minibuffer.

Cursor-umbrella detection reads the already-rendered CHLD cell via `tabulated-list-get-entry` at point (non-`-` means umbrella); no extra CLI call. Degrades gracefully to no-prefill if the column is ever absent.

## Notes

**2026-06-18T20:47:31.318733067Z**

knot.el ,P parent filter now offers umbrella-only candidates and pre-fills the umbrella id at point. knot-update--read-parent gained an opt-in umbrellas-only arg (seq-filter children_total>0; assign/create callers unchanged); knot-list--cursor-umbrella-id reads the rendered CHLD cell at point (no extra CLI call) and feeds the helper's existing current pre-fill with precedence cursor>active-filter>empty. C-u still widens to closed/archived umbrellas. README f-transient docs updated. Verified via batch-emacs functional checks; bb lint:elisp clean.
