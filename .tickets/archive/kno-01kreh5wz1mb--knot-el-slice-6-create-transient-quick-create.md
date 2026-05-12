---
id: kno-01kreh5wz1mb
title: 'knot.el slice 6: create transient + quick-create + start/close transitions'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:44:01.872296601Z'
updated: '2026-05-12T21:08:30.072947210Z'
closed: '2026-05-12T21:08:30.072947210Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: c opens a create transient with infix args for type, priority, mode, tags, assignee, parent, deps, links, acceptance, external-refs
  done: true
- title: Title is prompted in the minibuffer at commit time (not as an infix)
  done: true
- title: Infix completions and defaults are sourced from the cached knot info envelope
  done: true
- title: 'Post-create lands in *knot-show: <project> · <id>* with point on ## Description'
  done: true
- title: Capital C runs knot create with title and no other flags, drops into show
  done: true
- title: s in list or show calls knot start <id> and refreshes
  done: true
- title: x in list or show prompts for a closing summary in the minibuffer and calls knot close <id> --summary
  done: true
- title: acceptance_incomplete from knot close surfaces as user-error in the minibuffer with the envelope message
  done: true
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

## Notes

**2026-05-12T21:08:05.266052326Z**

Dispatch transient: c/C for create/quick-create; closed view moved from c to o (still reachable in-buffer via list's c). AC #4 falls back to point-min when ticket has no body (no ## Description heading rendered) — point-on-Description is honoured when --description is supplied or the ticket later acquires a Description section. End-to-end smoke: knot-create--run via batch Emacs against /tmp/knot-test exercised both branches (with/without body) and the acceptance-gate user-error path.

**2026-05-12T21:08:30.072947210Z**

Slice 6 shipped at emacs/knot.el. `knot-create` is a transient prefix (10 option infixes: type/priority/mode/tags/assignee/parent/deps/links/acceptance/external-refs; comma-list infixes expand into repeatable argv pairs at commit; title prompted on commit). `knot-create-quick` is title-only with defaults. Both drop into *knot-show* with point on ## Description (or point-min when body empty). `knot-start`/`knot-close` bound to s/x in list+show keymaps; close surfaces acceptance_incomplete via existing knot-cli--parse user-error path. Dispatch transient rebinds c/C to create/quick-create; closed view moves to o. Byte-compile clean under Emacs 28.1; 347 bb tests pass. End-to-end batch smoke verified create-with-body lands on ## Description and acceptance gate raises the envelope message.
