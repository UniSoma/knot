---
id: kno-01m0jbrv6wbe
title: Wave-based orchestration support for umbrella tickets
status: open
type: task
priority: 2
mode: hitl
created: '2026-08-21T14:32:03.548040980Z'
updated: '2026-08-21T14:32:03.548040980Z'
tags:
- agents
- orchestration
---

## Description

Umbrella for the requests in an RFI drafted 2026-08-21 against knot v0.10.0 by an orchestrator running an umbrella's children in waves (the frontier = ready direct children; one Workflow run per wave; commit + AC flip + close between waves). Trial umbrella on a client project: 15 children, 9 open across 2 waves; building the sibling DAG took 9 calls, AC titles run 150–250 chars, LEV/CPL/CC appear in tables but not in help.

Each child is one agreed change. Withdrawn from the RFI after grilling: a sibling-DAG render (list --parent --json already carries deps per row; level makes it unnecessary), a knot claim command (a conditional write on start/update is the primitive), a dedicated commit field (external_refs with a git:<sha> convention), a touch-area field and --disjoint (an area:<brick> tag convention, orchestrator-side), and a new narrative section on prime.

Glossary: Level is defined in CONTEXT.md. Conventions that belong to the orchestrator, not knot: area:<brick> tags, git:<sha> external refs.
