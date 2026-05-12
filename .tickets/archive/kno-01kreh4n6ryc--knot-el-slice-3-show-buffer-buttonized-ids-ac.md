---
id: kno-01kreh4n6ryc
title: 'knot.el slice 3: show buffer + buttonized ids + AC interaction'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:43:21.169045501Z'
updated: '2026-05-12T19:00:55.602056452Z'
closed: '2026-05-12T19:00:55.602056452Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: 'RET on a list row opens *knot-show: <project> · <id>*; revisiting the same id reuses the buffer'
  done: true
- title: Show buffer fontifies markdown headings, lists, and code blocks (derives from markdown-view-mode)
  done: true
- title: Every ticket id rendered in the show buffer is a button; RET on an id opens that ticket's show in the same window
  done: true
- title: '] and [ in show flip to the next/previous ticket in the originating list buffer; entries reached via dep/link/parent do not set the stash'
  done: true
- title: RET on an acceptance criterion line calls knot update --ac with --done or --undone and re-renders the buffer
  done: true
- title: Pressing + (or a) in the AC section prompts for and adds a new criterion via knot update --add-ac
  done: true
- title: Pressing minus (or k) on an AC line removes the criterion via knot update --remove-ac after a yes/no confirmation
  done: true
- title: q walks the entry chain (list → show → drilled-in show), falling back to quit-window when the chain is exhausted
  done: true
deps:
- kno-01kreh377wy0
---

## Description

Add the show buffer landed from the list view. *knot-show: <project> · <id>* renders one ticket with markdown fontification (via markdown-view-mode), buttonized ticket ids everywhere they appear (deps, links, parent, body references), and a local keymap on AC lines that flips done/undone via `knot update --ac --done|--undone`. + adds an AC; - removes one after confirmation.

] and [ navigate to the next/previous ticket from the originating list buffer (captured as buffer-locals on entry). Drilling in from a dep/link/parent does not set the ]/[ stash. q is the back button via quit-window — relies on Emacs's existing window history, not a custom stack.

Modules introduced: `knot-show` (shallow). `knot-id` gains its buttonize half here.

See docs/prd/knot-el.md user stories 8-15 and 'Drill-down navigation' for the navigation model.

## Notes

**2026-05-12T18:58:37.902572429Z**

q semantics changed: was 'calls quit-window'; now walks a buffer-local back-pointer chain so multi-level drill-in (list → A → B → C) pops one level per q press. Single-pointer (not stack) — see knot-show--back-buffer in emacs/knot.el. Caused by Emacs's per-window quit-restore being overwritten on every same-window display. PRD user story 11 + 'Drill-down navigation' updated to match.

**2026-05-12T19:00:55.602056452Z**

Slice 3 shipped at emacs/knot.el (commit dd83602). knot-show-mode derives markdown-view-mode and renders frontmatter + AC + body + relationships; knot-id-buttonize-region (driven by knot info's project.prefix) buttonises every id occurrence. RET dispatches button → AC flip → other-button. + / a add ACs (knot update --add-ac); - / k remove with yes-or-no confirmation (--remove-ac); RET on an AC line flips done/undone (--ac --done|--undone). ]/[ step through the originating list buffer without growing the back chain. q walks a buffer-local knot-show--back-buffer chain (list → show → drilled-in show), falling back to quit-window when exhausted — needed because Emacs's per-window quit-restore is overwritten on each same-window display. Side effects: markdown-mode promoted to a hard require; markdown-mode's GFM-checkbox after-change hook removed locally so  AC rows don't get shadowed. Verified via byte-compile + package-lint clean, 347/347 bb test pass, end-to-end CLI round-trip on AC ops, keybinding audit, and a three-level drill-in q-chain walk. PRD user story 11 + 'Drill-down navigation' updated to document the back-pointer chain; AGENTS.md / SKILL.md untouched (no CLI surface change).
