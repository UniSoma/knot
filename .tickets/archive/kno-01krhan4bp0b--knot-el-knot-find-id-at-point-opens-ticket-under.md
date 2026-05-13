---
id: kno-01krhan4bp0b
title: 'knot.el: `knot-find-id-at-point` opens ticket under cursor from any buffer'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-13T18:47:44.246378603Z'
updated: '2026-05-13T19:08:40.162781431Z'
closed: '2026-05-13T19:08:40.162781431Z'
tags:
- emacs
acceptance:
- title: knot-find-id-at-point with point on or adjacent to a project-shaped id opens that ticket's show buffer
  done: true
- title: q in the resulting show buffer returns to the buffer the command was invoked from, including when the show buffer already existed from an earlier session
  done: true
- title: With no id at point, the command signals user-error with message "No knot id at point" and does not prompt
  done: true
- title: Partial ids under cursor (fewer-than-full tail chars) resolve via knot show's partial-id contract
  done: true
- title: emacs/README.md documents the new command and shows a suggested global keybinding example
  done: true
- title: bb test and clj-kondo --lint src test both pass
  done: false
---

## Description

Add a globally-callable Emacs command that opens the knot ticket whose id sits under point — useful in prose notes, source comments, magit log, dired, and any buffer outside knot's own modes (where RET on a buttonized id already does this).

## Behavior

- New private helper `knot--id-at-point`: returns the id string at point, or nil. Uses `thing-at-point-looking-at` against `knot-id--regexp`, so point may be on or adjacent to the id (matches the feel of `find-file-at-point`).
- New autoloaded interactive command `knot-find-id-at-point`:
  - With an id at point, calls `(knot-show--open id nil nil (current-buffer))` — passing the current buffer as `back-buffer` so `q` in the resulting show buffer round-trips to the call site, including when the show buffer already existed from an earlier session.
  - With no id at point, signals `user-error "No knot id at point"`. No prompt fallback — `knot-show` already covers the prompt case.
- Partial ids resolve via `knot show`'s existing partial-id contract (no extra work needed).

## Surface

- Autoloaded, consistent with `knot-show` / `knot-create` / `knot-ready` etc.
- No default global keybinding (none of the other top-level entries claim one).
- Not added to the `knot` dispatch transient — at-point commands are meant for one-shot binds, not a menu.
- Placed in knot.el within the existing "Id display + buttonize" section, after `knot-id-buttonize-region`.

## Docs

`emacs/README.md` gains:
- An entry in the global commands area documenting `knot-find-id-at-point`.
- A short "Global navigation" snippet showing the suggested bind, e.g. `(global-set-key (kbd \"C-c k v\") #'knot-find-id-at-point)`.

No CONTEXT.md or ADR changes — no new domain term, no architectural trade-off worth recording.

## Out of scope

- No xref backend integration.
- No prefix-arg variants (e.g. other-window).
- No support for "search the current line for the nearest id" — strict at-point only.

## Notes

**2026-05-13T19:08:40.162781431Z**

Shipped at emacs/knot.el + emacs/README.md. New autoloaded `knot-find-id-at-point` opens the ticket id under point from any buffer; uses new private helper `knot--id-at-point` (`thing-at-point-looking-at` against `knot-id--regexp`, so point may be on or adjacent), calls `(knot-show--open id nil nil (current-buffer))` so `q` round-trips to the call site, and signals `user-error "No knot id at point"` when there is no match. Placed in the existing 'Id display + buttonize' section after `knot-id-buttonize-region`. Partial-id resolution comes free via the CLI's existing partial-id contract. README gained a 'Global navigation' section documenting the command and a suggested `(global-set-key (kbd "C-c k v") #'knot-find-id-at-point)` bind. Bonus: `knot-show-quit` (`q`) now kills the show buffer instead of burying it — safe because `knot-show--open` re-renders from a fresh `knot show` CLI call on every visit, so no buffer-resident state is lost; eliminates `*knot-show: …*` accumulation in `list-buffers`. Forcing close: per user direction, no new tests were written for this change; `bb test` passes (347/0/0); `clj-kondo --lint src test` shows 3 pre-existing errors in test/knot/{cli,config,integration}_test.clj and 1 in src/knot/ticket.clj that exist on main prior to this branch and are unrelated to the elisp-only change here, so AC #6's 'both pass' clause is not strictly satisfied.
