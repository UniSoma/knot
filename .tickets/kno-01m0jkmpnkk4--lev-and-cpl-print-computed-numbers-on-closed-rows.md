---
id: kno-01m0jkmpnkk4
title: LEV and CPL print computed numbers on closed rows under list --status closed
status: open
type: bug
priority: 3
mode: afk
created: '2026-08-21T16:49:36.434934994Z'
updated: '2026-08-21T16:49:49.709050755Z'
tags:
- listing
- columns
- graph
acceptance:
- title: A closed row under list --status closed renders - for LEV and CPL, matching LVL
  done: false
- title: A listing mixing live and closed rows keeps computed numbers on the live rows
  done: false
- title: leverage and coupling emit null for closed rows in --json, and the contract pins cover it
  done: false
- title: help notes for list/ready/blocked and references/json-protocol.md state that the three graph metrics are live-only
  done: false
links:
- kno-01m0jbrvd2be
---

## Description

ADR 0011 declared leverage absent from `closed`, but that covered the `closed` *command*. The other route to a closed row — `knot list --status closed` — still renders LEV and CPL, and they print numbers.

Reproduced on a three-ticket chain A <- B <- C with A closed:

    ID                STATUS  ...  LEV  CPL  LVL  TITLE
    tst-01m0jkhjphfp  closed  ...    2    1    -  A leaf

Leverage answers "if I close this, how much downstream work stops waiting?" — a question already settled for a closed ticket, and B is `ready` the moment A closes. Reporting 2 invites a reader to treat a finished ticket as a keystone. Both metrics are defined over the live-induced subgraph (ADRs 0011 and 0012) and a closed ticket is not a node of it, so a number there is not a smaller truth, it is a category error.

`level` already takes the honest position: it is keyed only by live tickets, so a closed row reads `-` / `null` (ADR 0018, which records this divergence and explicitly does not fix it). This ticket is about bringing LEV and CPL into line with LVL, not the reverse.

## Design

Decide first whether the fix is to blank the cells (matching LVL) or to drop the columns entirely when a listing carries no live rows. Blanking is likely right: `list --status closed` can be mixed with other filters, so a listing may hold live and closed rows at once, and per-row blanking handles that where per-table column dropping does not.

Touch points: the `annotate-*` enrichment in `cli.clj` for `ls-cmd`, and the `jsonify-ticket` key-presence gates in `output.clj`. `query/levels` is the model — it keys the map by live tickets only and lets `annotate-level` read it with `get`.

JSON is a shape change: `leverage` and `coupling` become nullable on closed rows, so `references/json-protocol.md` and the json-contract pins move with it.
