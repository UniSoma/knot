---
id: kno-01ks09bp57z5
title: 'emacs: knot-delete (K) — bare + cascade flow'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-19T14:13:42.684902738Z'
updated: '2026-05-19T14:34:41.724911809Z'
closed: '2026-05-19T14:34:41.724911809Z'
tags:
- needs-triage
acceptance:
- title: '`K` in `knot-list-mode` invokes `knot-delete` against the row at point; with no row, signals `user-error`'
  done: true
- title: '`K` in `knot-show-mode` invokes `knot-delete` against `knot-show--id`'
  done: true
- title: The bare y/n prompt formats the title (truncated to a sensible width) so a fat-finger on the wrong row is visible; `n` aborts with no side effects
  done: true
- title: 'Bare success: the deleted ticket''s show buffer (if any) is killed and the back-buffer chain restores the originating buffer; `knot--after-mutation` propagates the removed row to visible list buffers; `Deleted <id>.` echoes to the minibuffer'
  done: true
- title: 'On `has_incoming_refs` refusal, `*knot-delete: <id>*` opens in `knot-delete-refusal-mode`: read-only, `q` dismisses, body lists one referrer per line in the form `<id>  (<:field>)` with the id buttonized to open its show buffer'
  done: true
- title: Cascade `y` kills the popup, runs `knot delete --cascade`, and on success refreshes the same way as bare, emitting `Deleted <id>; cascaded across N referrers.` — or `Deleted <id>.` when `data.cleaned` is empty (leaf cascade)
  done: true
- title: Cascade `n` leaves the popup open; `q` dismisses; `RET` on a referrer id opens that ticket's show buffer
  done: true
- title: New `knot-cli-call-envelope` returns the full envelope verbatim and does not raise on `ok:false`; `knot-cli--check-cli-version` still runs from the new helper
  done: true
- title: '`knot-cli-call` is refactored as a thin wrapper over `knot-cli-call-envelope`; no other call site is touched'
  done: true
- title: '`knot-evil--bindings` drops `K` → `knot-show-remove-at-point`; both list and show maps (stock + evil) bind `K` → `knot-delete`'
  done: true
- title: Marks in `knot-list-mode` are ignored — `K` operates on the row at point only, mirroring `s`/`x`
  done: true
- title: '`emacs/README.md` binding table updated: `K` rows added under list and show; `-` / `K` row for `knot-show-remove-at-point` reduced to `-`; a short paragraph describes the bare → popup → cascade flow'
  done: true
- title: '`bb test` and `clj-kondo --lint src test` both clean'
  done: true
deps:
- kno-01kryx804m7g
---

## Description

Add `knot-delete` to `emacs/knot.el`: bound to `K` in `knot-list-mode` and `knot-show-mode`. Single-id only (row at point in list, `knot-show--id` in show); marks ignored — parity with `s`/`x`. Two-step destructive flow that mirrors the CLI's bare-default + `--cascade` opt-in (ADR-0008): a y/n confirm runs bare `knot delete`; on `has_incoming_refs` refusal, a popup buffer surfaces the referrer list with buttonized ids and a second y/n offers `--cascade`. The popup outlives a "no" answer so the user can inspect/edit referrers and re-invoke. Reclaims `K` from the redundant evil alias to `knot-show-remove-at-point` (which keeps `-`).

Threads through a new `knot-cli-call-envelope` helper that returns the full JSON envelope verbatim — needed because `error.referrers` is structured and `knot-cli-call` currently discards everything but `error.message`. `knot-cli-call` becomes a thin wrapper; no existing call site changes.

## Design

Flow (invoked from list-row or show buffer):

1. `y-or-n-p "Delete <id> — <title>? "` — `n` aborts silently.
2. `y` → `knot-cli-call-envelope '("delete" id)`.
3. **Bare success** (`ok:true`): walk visible buffers, kill any `knot-show-mode` buffer whose `knot-show--id` matches the deleted id (uses `knot-show--back-buffer` to land the user in the originating list); `knot--after-mutation`; `message "Deleted %s." id`.
4. **Bare refusal** with `error.code = "has_incoming_refs"`: `pop-to-buffer` a new `*knot-delete: <id>*` buffer in `knot-delete-refusal-mode` (read-only, `q` → `quit-window`). Body lists one referrer per line: `<id>  (<:field>)` — id is a button opening the referrer's show buffer via `knot-id-buttonize-region`. Then `y-or-n-p "<id> has N referrer(s). Cascade delete? "`.
5. **Cascade `n`**: popup left open. `q` dismisses. User can `RET` into referrers, fix them, then re-invoke `K`.
6. **Cascade `y`**: `kill-buffer` the popup, `knot-cli-call-envelope '("delete" id "--cascade")`.
7. **Cascade success**: same kill + walker refresh as bare. Message: `Deleted <id>; cascaded across N referrers.` — or plain `Deleted <id>.` when `data.cleaned` is empty (leaf cascade, silent no-op per the CLI contract).
8. **Cascade error** (partial-failure, etc.): the standard `user-error` surfaces `error.message`; idempotent retry per the CLI contract.

CLI boundary (new sibling helper):

```elisp
(defun knot-cli-call-envelope (args &optional stdin)
  "Run knot with ARGS, return the parsed envelope alist verbatim.
Unlike `knot-cli-call', does not raise on ok:false — caller inspects.
Runs `knot-cli--check-cli-version' on success envelopes as
`knot-cli-call' does today.")
```

`knot-cli-call` becomes:

```elisp
(let ((env (knot-cli-call-envelope args stdin)))
  (if (alist-get 'ok env)
      (alist-get 'data env)
    (user-error "knot: %s"
                (alist-get 'message (alist-get 'error env)))))
```

`knot-evil--bindings` changes: remove the line `("K" . knot-show-remove-at-point)` from `knot-show-mode-map`. Add `("K" . knot-delete)` to both `knot-list-mode-map` and `knot-show-mode-map`. Stock keymaps (`knot-list-mode-map`, `knot-show-mode-map`) also get `(keymap-set map "K" #'knot-delete)`.

## Notes

**2026-05-19T14:34:41.724911809Z**

Shipped: K in knot-list-mode and knot-show-mode invokes knot-delete against the row/ticket at point (marks ignored, mirroring s/x). Bare y/n confirm runs knot delete; on has_incoming_refs refusal, a *knot-delete: <id>* popup in knot-delete-refusal-mode lists referrers as '<id>  (:<field>)' with buttonized ids and a second y/n offers --cascade. Cascade n leaves the popup open so the user can RET into a referrer, fix it, and re-invoke K. Success kills the deleted ticket's show buffer (back-buffer chain restores the originating buffer), runs knot--after-mutation, and echoes 'Deleted <id>.' — or 'Deleted <id>; cascaded across N referrers.' on a non-leaf cascade. New knot-cli-call-envelope returns the full envelope verbatim (no raise on ok:false); knot-cli-call is now a thin wrapper. Evil bindings updated: K dropped from knot-show-remove-at-point, added to both list+show. README binding table + bare→popup→cascade paragraph updated. bb test (410 tests / 4916 assertions), clj-kondo, and emacs byte-compile all clean.
