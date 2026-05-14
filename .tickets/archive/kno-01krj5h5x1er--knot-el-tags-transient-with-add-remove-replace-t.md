---
id: kno-01krj5h5x1er
title: 'knot.el: tags transient with add/remove/replace (T prefix)'
status: closed
type: feature
priority: 0
mode: afk
created: '2026-05-14T02:37:26.305560051Z'
updated: '2026-05-14T13:58:53.226239512Z'
closed: '2026-05-14T13:58:53.226239512Z'
tags:
- emacs
- knot-el
- v0.5
acceptance:
- title: knot-tags-transient defined with suffixes a (add), r (remove), T (replace); bound to T in knot-list-mode-map, knot-show-mode-map, and both knot-evil--bindings blocks
  done: true
- title: T tags suffix removed from knot-update-from-show; the other seven frontmatter suffixes unchanged
  done: true
- title: knot-update--commit-args ids args primitive introduced; knot-update--commit rewritten as a thin shim; existing scalar updates (status/priority/mode/type/assignee/parent/replace-tags) still work end-to-end
  done: true
- title: 'knot-update-add-tags: completing-read-multiple with project-wide candidates from knot list --json (live); empty input is a silent no-op; expands to repeated --add-tag X argv; honors knot-list--marks for fan-out'
  done: true
- title: 'knot-update-remove-tags: completing-read-multiple with current-ticket tags (single id) or union across marked ids (bulk) as candidates; empty input is a silent no-op; expands to repeated --remove-tag X argv; honors knot-list--marks for fan-out'
  done: true
- title: 'knot-show-add-at-point recognizes (get-text-property (point) ''knot-field) == ''tags and dispatches to knot-update-add-tags; + on the tags: line in a show buffer adds'
  done: true
- title: 'knot-show-remove-at-point recognizes ''tags field and dispatches to knot-update-remove-tags; - on the tags: line in a show buffer removes'
  done: true
- title: 'RET on the tags: line in a show buffer continues to invoke replace (knot-show--field->command unchanged)'
  done: true
- title: Lint clean (clj-kondo --lint src test) and emacs byte-compiles without warnings (cd emacs && emacs -Q -batch -L . -f batch-byte-compile knot.el)
  done: true
---

## Description

Add a top-level `knot-tags-transient` (bound to `T`, parallel to `D` deps / `L` links) that exposes three tag operations: `a` add, `r` remove, `T` replace. Replaces the current single-suffix `T tags` entry inside `knot-update-from-show`.

Drives the underlying CLI's `--add-tag` / `--remove-tag` / `--tags` flags on `knot update` so users can do delta tag edits from emacs instead of only full replacement.

## Surface

- New `knot-tags-transient` (header "Tags"):
  - `a` add → new `knot-update-add-tags`
  - `r` remove → new `knot-update-remove-tags`
  - `T` replace → existing `knot-update-set-tags` (unchanged)
- Bound to `T` in `knot-list-mode-map`, `knot-show-mode-map`, and both `knot-evil--bindings` blocks (full parallel with `D` / `L`).
- `("T" "tags" knot-update-set-tags)` removed from `knot-update-from-show`.
- Show-mode field dispatch on `tags:` line:
  - RET → unchanged (still invokes replace via `knot-show--field->command`)
  - `+` → new `'tags` arm in `knot-show-add-at-point` → `knot-update-add-tags`
  - `-` → new `'tags` arm in `knot-show-remove-at-point` → `knot-update-remove-tags`

## Prompt UX

- Both add and remove use `completing-read-multiple`. Empty input is a silent no-op (no subprocess).
- `add` candidates: project-wide tag union from one `knot list --json` (live only). Free input still allowed.
- `remove` candidates: current ticket's tags (single id) or union across `knot-list--marks` (bulk), both from the same `knot list --json`. Free input allowed; CLI is idempotent on missing matches.

## Plumbing

- New primitive `knot-update--commit-args ids args` taking a flat argv vector; owns the per-id loop, `condition-case` error capture, single `knot--after-mutation`, and summary `message` ("N: K ok" / "N: K ok, F failed: id (reason), ...").
- Existing `knot-update--commit ids flag value` refactored to a thin shim: `(knot-update--commit-args ids (list flag value))`. Seven existing scalar callers unchanged.
- `--add-tag` / `--remove-tag` reject comma-bearing values at the CLI; emacs side expands the comma-list into repeated argv flags before invoking `knot update`.

## Fan-out

- Both new operations use `knot-update--ticket-ids`, so `knot-list--marks` is honored: one subprocess per id with the full argv (`--add-tag X --add-tag Y …`), error per id captured, summary `message` at the end. Behavior matches the existing scalar bulk path.

## Notes

**2026-05-14T13:58:53.226239512Z**

shipped in dfe60a2 — knot.el tags transient (T → add/remove/replace) with --add-tag / --remove-tag delta CLI flags wired through emacs
