---
id: kno-01kqa804gmgx
status: closed
type: epic
priority: 2
mode: hitl
created: '2026-04-28T14:30:56.276707258Z'
updated: '2026-04-29T14:01:05.191686490Z'
closed: '2026-04-29T14:01:05.191686490Z'
external_refs:
- docs/prd/knot-v0.md
deps:
- kno-01kqa6xf42q9
- kno-01kqa6xf597w
- kno-01kqa6xf6gtf
- kno-01kqa6xf7mde
- kno-01kqa6xf8rcr
- kno-01kqa6xf9s1k
- kno-01kqa6xfb39t
- kno-01kqa6xfc777
- kno-01kqa6xfd7aw
- kno-01kqa6xfe80k
- kno-01kqa6xff7ya
---

# Knot v0 — babashka ticket tracker

## Description

Umbrella ticket for the Knot v0 PRD. Body intentionally thin — see the PRD doc for the full spec. Children (computed from :parent backrefs) are the implementation issues 0001-0011.

## Notes

**2026-04-29T14:01:05.191686490Z**

All 11 v0 child tickets (0001-0011) closed. Release infrastructure (kno-01kqb7833py3) in place: version constant 0.0.1, CHANGELOG, refreshed /release slash command. Test suite green (164 tests, 1465 assertions, 0 failures). Ready to cut v0.0.1 tag. Remaining open tickets are explicitly post-v0: kno-01kqa9shmqf3 (AC checkbox CLI, tagged v0.1), kno-01kqcpb0t5s7 (distribution refinement), kno-01kqcpw6bzn6 (GHA CI).
