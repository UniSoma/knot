---
id: kno-01krh0y8g4sq
title: 'knot.el: mark-aware update dispatch on list rows'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-13T15:57:57.628361095Z'
updated: '2026-05-13T17:16:14.130330445Z'
closed: '2026-05-13T17:16:14.130330445Z'
tags:
- emacs
- knot-el
acceptance:
- title: knot-update-from-show invoked in knot-list-mode with knot-list--marks non-empty fans out across the marked set; with empty marks, falls back to (tabulated-list-get-id) (current behavior)
  done: true
- title: Bulk prompts prefix the prompt with '(N marked)' and pass nil default to completing-read / read-string so plain RET does not silently pick a value
  done: true
- title: Bulk commit loops one knot update subprocess per id, continues past user-error failures, accumulates (id . reason), and runs knot--after-mutation exactly once after the loop
  done: true
- title: 'On completion, a message of the form ''M: K ok'' or ''M: K ok, F failed: <id> (reason), ...'' summarizes the run'
  done: true
- title: Marks persist across a successful or partially-failed bulk run; auto-prune (slice 1) handles rows that disappear from the current view
  done: true
- title: 'Show-mode invocation path is unchanged: single ticket, current-value default still prefills, no ''(N marked)'' prefix'
  done: true
deps:
- kno-01krh0xhf31f
---

## Description

Once `knot-list-mode` has marks (kno-01krh0xhf31f), `knot-update-from-show` should fan out across the marked set when invoked from a list buffer. Without marks, behavior is unchanged (operates on row at point). With marks, each frontmatter suffix prompts once and applies the chosen value to every marked ticket, continuing past errors and reporting at the end. This is the payoff slice for the marking work — the user's stated motivator: set status / priority / mode / tags / assignee / parent on N tickets in one breath.

## Design

**Entry point.** No keymap changes. `knot-update-from-show` itself becomes mark-aware, so whatever key happens to be bound to it (`,` upstream, `M` in user Doom configs) inherits the bulk behavior transparently. Show-mode flow stays identical.

**Resolver shape.** `knot-update--ticket-id` today returns a single id. Replace with a function that returns a *list of ids*:

- In `knot-show-mode`: `(list knot-show--id)` (single-element list).
- In `knot-list-mode`: if `knot-list--marks` is non-empty, return the marked ids in display order; otherwise `(list (tabulated-list-get-id))`.
- Outside both modes: `user-error` as today.

Each `knot-update-set-*` suffix iterates over the returned list. For len=1 paths the behavior is unchanged (current-value default still prefills the minibuffer); for len>1 paths the default is dropped (see prompts below).

**Prompts.** When the id list has length > 1:

- Pre-prompt prefix: each reader's prompt gains a `(N marked)` chunk — e.g. `status (3 marked): `, `priority (3 marked, 0..4): `, `tags (3 marked, comma-list, empty to clear): `.
- No default value: `completing-read` / `read-string` are called with the default argument stripped so plain RET on an empty minibuffer does not silently pick a value. Rationale: any single default lies for some subset of the marked rows, and bulk mode is a write operation by user intent.

**Commit fan-out.** Replace `knot-update--commit`'s single `(knot-cli-call (list "update" id flag value))` with a loop:

- For each id, run `knot update <id> <flag> <value>` as a separate subprocess.
- Catch `user-error` from the CLI envelope; record `(id . reason)` to a failure list and continue.
- After the loop, run `knot--after-mutation` exactly once.
- Emit `(message "M: K ok, F failed: kno-… (reason), kno-… (reason)")` summarizing the result. When F=0, the message reads `M: K ok`.

**Mark persistence.** Marks are *not* consumed by the operation; the auto-prune contract from slice 1 handles cases where successful mutations cause rows to disappear from the current view (e.g. setting status to closed in the `ready` view). The user clears marks explicitly via `U` (or by switching view).

**Long-form group.** Already guarded `:if-derived knot-show-mode` — no change needed; list-row invocations only see the frontmatter group, which is the only thing that makes sense in bulk.

**No tests.** `knot.el` has no elisp test infrastructure yet; manual user-test via the README walkthrough. When elisp tests land, backfill coverage for the fan-out path, the continue-on-error contract, and the empty-marks fallback to row-at-point.

**Out of scope.** Bulk `s` (start) and `x` (close) — explicitly excluded; they keep operating on row-at-point. Bulk long-form (description / design / body / note) — semantically dubious across N tickets.

## Notes

**2026-05-13T17:16:14.130330445Z**

Shipped at emacs/knot.el + emacs/README.md. knot-update-from-show in knot-list-mode now fans out across knot-list--marks: new resolver knot-update--ticket-ids returns marked ids in display order (helper knot-list--marks-in-display-order walks the rendered buffer), each knot-update-set-* builds a bulk-aware prompt with a '(N marked)' chunk and suppresses the default, and knot-update--commit accepts a list of ids — for >1 it loops one knot update subprocess per id, catches user-error from the CLI envelope as (id . reason), continues past failures, runs knot--after-mutation once after the loop, and emits 'M: K ok' or 'M: K ok, F failed: <id> (reason), ...'. Readers --read-priority / --read-tags / --read-parent gained an optional count param. Single-id paths (show-mode + list-mode without marks) preserve current behavior. Marks persist across the run; rows that fall out of view auto-prune via the existing repaint intersect. README's marks section replaces the 'follow-up slice' placeholder with the bulk fan-out contract. byte-compile clean; bb test 347 / 4399 green.
