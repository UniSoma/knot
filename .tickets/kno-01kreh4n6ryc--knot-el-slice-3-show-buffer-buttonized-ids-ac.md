---
id: kno-01kreh4n6ryc
title: 'knot.el slice 3: show buffer + buttonized ids + AC interaction'
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:21.169045501Z'
updated: '2026-05-12T16:43:21.169045501Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: 'RET on a list row opens *knot-show: <project> · <id>*; revisiting the same id reuses the buffer'
  done: false
- title: Show buffer fontifies markdown headings, lists, and code blocks (derives from markdown-view-mode)
  done: false
- title: Every ticket id rendered in the show buffer is a button; RET on an id opens that ticket's show in the same window
  done: false
- title: '] and [ in show flip to the next/previous ticket in the originating list buffer; entries reached via dep/link/parent do not set the stash'
  done: false
- title: RET on an acceptance criterion line calls knot update --ac with --done or --undone and re-renders the buffer
  done: false
- title: Pressing + (or a) in the AC section prompts for and adds a new criterion via knot update --add-ac
  done: false
- title: Pressing minus (or k) on an AC line removes the criterion via knot update --remove-ac after a yes/no confirmation
  done: false
- title: q calls quit-window
  done: false
deps:
- kno-01kreh377wy0
---

## Description

Add the show buffer landed from the list view. *knot-show: <project> · <id>* renders one ticket with markdown fontification (via markdown-view-mode), buttonized ticket ids everywhere they appear (deps, links, parent, body references), and a local keymap on AC lines that flips done/undone via `knot update --ac --done|--undone`. + adds an AC; - removes one after confirmation.

] and [ navigate to the next/previous ticket from the originating list buffer (captured as buffer-locals on entry). Drilling in from a dep/link/parent does not set the ]/[ stash. q is the back button via quit-window — relies on Emacs's existing window history, not a custom stack.

Modules introduced: `knot-show` (shallow). `knot-id` gains its buttonize half here.

See docs/prd/knot-el.md user stories 8-15 and 'Drill-down navigation' for the navigation model.
