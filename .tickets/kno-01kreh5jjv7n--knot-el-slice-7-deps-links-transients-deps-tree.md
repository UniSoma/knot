---
id: kno-01kreh5jjv7n
title: 'knot.el slice 7: deps + links transients + deps tree buffer'
status: in_progress
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:51.251988806Z'
updated: '2026-05-12T20:28:25.755983936Z'
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
