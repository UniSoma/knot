---
id: kno-01kqsgmeycvx
title: Remove canonical status fallback literals from ls-table
status: closed
type: chore
priority: 3
mode: afk
created: '2026-05-04T12:50:27.404461887Z'
updated: '2026-05-05T11:03:50.712447493Z'
closed: '2026-05-05T11:03:50.712447493Z'
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

## Notes

**2026-05-05T11:03:50.712447493Z**

Drop the ["open" "in_progress" "closed"] / #{"closed"} / "in_progress" :or fallback in knot.output/ls-table and source missing :statuses / :terminal-statuses / :active-status from knot.config/defaults. Eliminates the latent coupling flagged in the v0.3 audit (kno-01kqgqapwqvh): future call sites that forget to thread status context now route through the centralized v0 schema source instead of a renderer-local copy. RED→GREEN: new ls-table-fallback-sources-from-config-defaults-test rebinds (config/defaults) to {:statuses ["open" "active" "review" "closed"] :active-status "active"} and asserts the rebound active lane wraps in the :yellow SGR — fails before the fix (active reads as :other → no color), passes after. Existing ls-table-no-status-options-back-compat-test still green: config/defaults returns the v0 literals, so the back-compat rendering is preserved. CLI callers (ls/ready/blocked/closed) keep threading explicit status context via ls-table-opts; the renderer fallback is now a single-source safety net rather than a duplicate of the canonical defaults. Tests: 290/2660/0 failures (was 289/2657; +1 deftest, +3 assertions). Lint baseline unchanged (4 errors / 5 warnings, all pre-existing). Files: src/knot/output.clj, test/knot/output_test.clj.
