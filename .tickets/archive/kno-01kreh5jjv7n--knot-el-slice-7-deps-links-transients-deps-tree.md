---
id: kno-01kreh5jjv7n
title: 'knot.el slice 7: deps + links transients + deps tree buffer'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:51.251988806Z'
updated: '2026-05-12T20:54:27.413439488Z'
closed: '2026-05-12T20:54:27.413439488Z'
tags:
- emacs
- knot-el
acceptance:
- title: D opens a deps transient with add, remove, and tree-open actions
  done: true
- title: L opens a symmetric links transient with add and remove actions
  done: true
- title: Dep/link add uses completing-read with live tickets, displaying 'id  title' as candidates
  done: true
- title: Dep/link remove candidate lists include archived tickets
  done: true
- title: k on an existing dep or link id line in show removes that relationship after a yes/no confirmation
  done: true
- title: '*knot-deps: <project> · <id>* renders the JSON tree as an indented outline with checkmark/circle status glyphs for closed/live'
  done: true
- title: Each node in the deps buffer is a button; RET opens that ticket's show
  done: true
- title: f toggles between collapsed (knot dep tree) and --full rendering
  done: true
deps:
- kno-01kreh4n6ryc
parent: kno-01krebyvdr1w
---

## Description

Two dep/link management transients plus the deps tree buffer.

D in show or on a list row opens a deps transient with add / remove / tree-open actions. L mirrors it for symmetric links. Candidate selection uses `completing-read` against live tickets (deps add) or against live+archive (deps/links remove, so cleanups against closed work are possible). k on an existing dep/link line in show removes that relationship after a yes/no confirmation.

*knot-deps: <project> · <id>* renders `knot dep tree --json` as an indented outline with status glyphs (✓ for closed, ○ for live); each node is a button that opens that ticket's show. f toggles between collapsed (`knot dep tree`) and `--full` rendering.

Modules introduced: `knot-deps` (shallow — JSON-rendered tree view, status glyphs, node buttons).

See docs/prd/knot-el.md user stories 30-37 and 'Buffer architecture' for the tree-buffer model.

## Notes

**2026-05-12T20:54:27.413439488Z**

Slice 7 shipped at emacs/knot.el. `knot-deps-transient` (D) and `knot-links-transient` (L) — bound in show + list maps — expose a/add (completing-read over live tickets via `knot list`), r/remove (current deps/links, archive titles merged in via live + closed lookup), and t/tree-open (deps only). `knot-show-remove-at-point` widens `-` (and previously-AC-only `k`) to dispatch on text properties: Blockers rows (`knot-dep-id`) route to `knot undep <this> <row>`; Blocking rows (`knot-rdep-id`) route to `knot undep <row> <this>` so reverse-deps can be cleaned from either side; Linked rows (`knot-link-id`) route to `knot unlink`; AC lines (`knot-ac-title`) keep the existing `--remove-ac` path. All gated by `yes-or-no-p`. New `*knot-deps: <project> · <id>*` buffer (`knot-deps-mode`, derived from `special-mode`) renders `knot dep tree --json` as an indented outline: ✓ for closed, ○ for live, `?  (missing)` for dangling refs, trailing ` ↑` for seen-before duplicates in collapsed mode. Each node is a `knot-id` button; RET drills into show. `f` toggles collapsed vs --full via `knot-deps--full` buffer-local; `g` refreshes; `q` walks back via `knot-deps--back-buffer` mirroring the show buffer chain. `knot-refresh` extended to recognise `knot-deps-mode`. Two new faces: `knot-deps-seen-before` (inherits shadow) and `knot-deps-missing` (inherits font-lock-warning-face). bb lint:elisp clean, bb test 347/347 passing.
