---
id: kno-01kqsgmeycvx
title: Remove canonical status fallback literals from ls-table
status: open
type: chore
priority: 3
mode: afk
created: '2026-05-04T12:50:27.404461887Z'
updated: '2026-05-04T12:50:27.404461887Z'
parent: kno-01kqgqapwqvh
tags:
- v0.3
- audit
- cleanup
---

## Description

Audit finding from kno-01kqgqapwqvh.

Runtime site:
- src/knot/output.clj:421-423 bakes [\"open\" \"in_progress\" \"closed\"], #{\"closed\"}, and \"in_progress\" into ls-table's fallback status context.

Current CLI callers thread real config, so this is latent coupling rather than a live user-visible bug today. But the renderer still embeds canonical status literals instead of taking explicit context or a centralized defaults source, so future call sites can silently regress custom-status support.

Acceptance:
- ls-table no longer carries raw canonical status literals in its :or status-context fallback.
- Callers either pass explicit status context, or fallback flows through a single centralized source.
- Tests still cover custom-status rendering.
