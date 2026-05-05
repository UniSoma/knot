---
id: kno-01kqgqfwk4h1
title: v0.3 release plan and coordination
status: open
type: task
priority: 3
mode: hitl
created: '2026-05-01T02:57:07.684531365Z'
updated: '2026-05-05T01:38:54.088449090Z'
tags:
- v0.3
- release
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqbjg012
- kno-01kqgqa1jj1s
- kno-01kqgqcqmy19
- kno-01kqgqc2ks70
- kno-01kqgqdxbxye
- kno-01kqgqa7wnep
- kno-01kqgqegm782
- kno-01kqgqf4aw4j
links:
- kno-01kqcpb0t5s7
- kno-01kqcvp72htb
acceptance:
- title: Merge order pinned and recorded in this ticket's notes
  done: false
- title: AC migration timing decided (first-invocation auto-run / explicit `knot init --migrate` / bash script)
  done: false
- title: Single CHANGELOG entry drafted covering every v0.3 breaking change
  done: false
- title: Release notes drafted with migration guidance for v0.2 users
  done: false
- title: All v0.3-tagged blockers closed before tag push
  done: false
- title: '`bb test` green on all platforms (or Windows ticket kno-01kqcvp72htb explicitly resolved or deferred)'
  done: false
- title: v0.3 tag pushed; bbin install verified end-to-end
  done: false
---

## Description

Coordinate the v0.3 release once the v0.3 implementation slices are done. This is **not** the implementation work — it is the cut.

**v0.3 minimum bar (the slices that must land before tagging):**

1. kno-01kqgq9vhmvr — JSON envelope on read commands (foundation)
2. kno-01kqgqbjg012 — `--json` on all mutating commands
3. kno-01kqgqa1jj1s — Uniform filter flag set across listing + `prime`
4. kno-01kqgqcqmy19 — `knot update` command
5. kno-01kqgqc2ks70 — `knot check` command + `dep cycle` removal
6. kno-01kqgqdxbxye — Acceptance criteria promoted to frontmatter
7. kno-01kqgqa7wnep — `--afk`/`--hitl` shortcut removal
8. kno-01kqgqegm782 — `knot schema` introspection
9. kno-01kqgqf4aw4j — README + JSON protocol docs

**Sequencing decisions to settle:**

- Order of merges (envelope must land before `--json`-on-mutations; mutations + envelope must land before schema introspection; AC migration runs at cut, not before)
- Single bundled v0.3 PR vs sliced PRs landing on main as they're ready
- AC migration script timing — runs on first invocation post-upgrade, or as a one-time `knot init --migrate` command, or as a bash script in the release notes
- CHANGELOG drafting — coordinate the breaking-change list across slices; one consolidated CHANGELOG entry rather than per-slice noise
- Release notes phrasing — emphasize that v0.2 → v0.3 breaks (envelope shape, removed flags, AC migration); document the migration path

**Open follow-ups not blocking v0.3:**
- kno-01kqgqafcxvv — reopen atomicity verification (could land in v0.3 if found broken)
- kno-01kqgqapwqvh — hardcoded-literal audit (audit can run in parallel; child fixes may slip)
- kno-01kqgqd3vzx6 — stale-in-progress consolidation under `check` (hitl decision)
- kno-01kqgqaxzx98 — future-work optimistic concurrency (deferred)

**Cut steps:**

- All v0.3-tagged tickets above are closed
- `bb test` green on all platforms (depend on kno-01kqcvp72htb for Windows)
- CHANGELOG entry merged
- Tag pushed; bbin distribution updated (coordinate with kno-01kqcpb0t5s7 if distribution model is changing)
