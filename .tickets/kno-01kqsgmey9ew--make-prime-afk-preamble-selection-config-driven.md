---
id: kno-01kqsgmey9ew
title: Make prime AFK preamble selection config-driven instead of hardcoded afk
status: open
type: bug
priority: 3
mode: afk
created: '2026-05-04T12:50:27.401087881Z'
updated: '2026-05-04T12:50:27.401087881Z'
parent: kno-01kqgqapwqvh
tags:
- v0.3
- audit
- cleanup
---

## Description

Audit finding from kno-01kqgqapwqvh.

Runtime site:
- src/knot/output.clj:664 selects prime-preamble-afk only when mode-norm equals \"afk\".

That means `knot prime --mode <custom-agent-mode>` cannot reach the AFK preamble if a project customizes :modes away from [\"afk\" \"hitl\"]. The fix may require deriving the AFK/HITL role from config explicitly, or deciding/documenting that mode names are semantically fixed and should not be customized.

Acceptance:
- No runtime branch in prime renderer depends on the literal string \"afk\".
- Behavior is either config-derived for custom mode names, or the config/schema/docs explicitly constrain mode semantics so the coupling is intentional.
- Tests pin the chosen contract.
