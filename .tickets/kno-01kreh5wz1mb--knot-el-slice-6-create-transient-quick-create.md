---
id: kno-01kreh5wz1mb
title: 'knot.el slice 6: create transient + quick-create + start/close transitions'
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:44:01.872296601Z'
updated: '2026-05-12T16:44:01.872296601Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: c opens a create transient with infix args for type, priority, mode, tags, assignee, parent, deps, links, acceptance, external-refs
  done: false
- title: Title is prompted in the minibuffer at commit time (not as an infix)
  done: false
- title: Infix completions and defaults are sourced from the cached knot info envelope
  done: false
- title: 'Post-create lands in *knot-show: <project> · <id>* with point on ## Description'
  done: false
- title: Capital C runs knot create with title and no other flags, drops into show
  done: false
- title: s in list or show calls knot start <id> and refreshes
  done: false
- title: x in list or show prompts for a closing summary in the minibuffer and calls knot close <id> --summary
  done: false
- title: acceptance_incomplete from knot close surfaces as user-error in the minibuffer with the envelope message
  done: false
deps:
- kno-01kreh4yap1c
- kno-01kreh58t0ac
---

## Description

Create transient and lifecycle transitions.

c opens a knot-create transient with infix args for type, priority, mode, tags, assignee, parent, deps, links, acceptance, and external-refs; the title is prompted in the minibuffer at commit time. Infix completion sources come from knot info (allowed_values, defaults). On success, knot.el drops into the new ticket's show buffer with point parked on ## Description.

C (capital) is the quick-create: prompts only for a title, runs `knot create "title"` with all defaults, drops into show.

s in list or show calls `knot start <id>`. x prompts for a closing summary in the minibuffer and calls `knot close <id> --summary "..."`; an `acceptance_incomplete` envelope surfaces as user-error in the minibuffer with the envelope's message so the user understands the gate.

Modules introduced: `knot-create` (shallow — create transient + quick-create command).

See docs/prd/knot-el.md user stories 23-29 and 'Editing model'.
