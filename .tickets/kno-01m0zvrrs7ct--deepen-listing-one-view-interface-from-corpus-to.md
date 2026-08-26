---
id: kno-01m0zvrrs7ct
title: 'Deepen listing: one view interface from corpus to rows'
status: open
type: chore
priority: 3
mode: afk
created: '2026-08-26T20:21:45.894921449Z'
updated: '2026-08-26T20:24:26.905830824Z'
tags:
- listing
- columns
- refactor
acceptance:
- title: knot.listing exposes one fn from corpus to rows, and list/ready/blocked/closed-cmd are each a call to it plus render
  done: false
- title: Every computed column is one declaration in listing; output and help iterate the declarations and hold no per-column cond branches
  done: false
- title: prime-cmd builds its criteria through listing/criteria, not by hand
  done: false
- title: main/ls-handler is gone; list dispatches through list-handler
  done: false
- title: listing_test.clj covers source, scope, filter, limit and column attachment from a vector corpus with no temp dir
  done: false
- title: bb test and clj-kondo are green with every pre-existing cli_test listing test unchanged
  done: false
- title: CONTEXT.md carries the View entry
  done: false
---

## Description

The four views (list, ready, blocked, closed) each repeat the same pipeline by hand in cli.clj: source -> closure -> component -> filter-tickets -> sort -> limit -> annotate-*. The rule 'graph metrics attach only on live views, and only to live rows' therefore lives in four command bodies and again in output/ls-columns-for, value-of and jsonify-ticket; adding level (fa789c1) took nine coordinated edits, three of them byte-identical one-line appends. prime-cmd builds the filter criteria map a fifth time by hand. See CONTEXT.md **View** for the language this ticket makes structural.

## Design

New namespace knot.listing with one interface: corpus + terminal-statuses + {:source :scope :filters :limit} -> rows. Sources are named after the commands (:list :ready :blocked :closed); :list keeps its documented quirk (all tickets when :status is given, else non-terminal). Pipeline order byte-identical to today. The closed view's by-closed-desc sort is a :sort property of the view declaration. Each computed column (AC, CHLD, LEV, CPL, LVL, CC) is one declaration {:key :header :align :sources :attach :cell :json-field}; output renders whatever the rows' declarations say, so the cond-chains in ls-columns-for / value-of / jsonify-ticket go. Column layout unchanged. Help NOTES prose stays in help.clj keyed by column :key; help iterates listing/columns and help_test fails on a key with no note. prime-cmd calls listing/criteria for the filter projection only (wrapping its scalar --mode to a set); its sections stay its own. main/ls-handler collapses into (list-handler :list cli/ls-cmd). Leverage/coupling stay per-row recomputed (building the live graph once is separate work). Tests: new listing_test.clj with a vector corpus, rows out; existing temp-dir tests in cli_test.clj untouched and must stay green — they are the byte-identical guard. No ADR: reversible, not surprising, no real trade-off. Commit 1 is this ticket (refactor + CONTEXT.md View entry); commit 2 is bug kno-01m0jkmpnkk4 on the new seam.

## Notes

**2026-08-26T20:24:26.905830824Z**

Design was grilled with the maintainer on 2026-08-26; every open choice is settled in the Design section. The CONTEXT.md View entry (AC 7) is already written in the working tree, uncommitted — include it in the refactor commit, do not redraft it. Bug kno-01m0jkmpnkk4 depends on this ticket and is a separate follow-up commit on the new seam.
