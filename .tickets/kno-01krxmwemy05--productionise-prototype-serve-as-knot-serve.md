---
id: kno-01krxmwemy05
title: Productionise prototype/serve/ as 'knot serve' command
status: open
type: feature
priority: 4
mode: hitl
created: '2026-05-18T13:37:23.102315484Z'
updated: '2026-05-18T13:37:23.102315484Z'
tags:
- prototype
- web-ui
---

## Description

Promote the surviving prototype at `prototype/serve/` to a real `knot serve` CLI command.

Shape is fixed (single-column Stack — see docs/adr/0005-knot-serve-stack-layout.md). Scope is intentionally open until a dedicated design pass: auth model, bind address, mutation surface, daemon vs ephemeral, Babashka vs JVM, asset packaging, `bbin install` story, and relationship to knot.el all need deliberation.

Until this lands, the prototype stays runnable via `bb prototype:serve` (port 7777, read-only).
