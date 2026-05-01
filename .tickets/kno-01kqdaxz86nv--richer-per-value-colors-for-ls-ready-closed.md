---
id: kno-01kqdaxz86nv
title: Richer per-value colors for ls/ready/closed/blocked tables (type, priority, mode)
status: open
type: feature
priority: 3
mode: hitl
created: '2026-04-29T19:19:54.374266390Z'
updated: '2026-05-01T03:11:26.695691072Z'
links:
- kno-01kqgqapwqvh
- kno-01kqg37mssy3
---

## Description

The list-view tables (`knot ls`, `ready`, `closed`, `blocked`) already colorize the `:status` column per value, but `:type`, `:mode`, and `:priority` are mostly uniform — `:type` and `:mode` collapse to `[:faint]` regardless of value, and `:priority` only highlights `0`. Scanning a long list for, say, all bugs is harder than it should be.

**Current state:** `src/knot/output.clj:230-241` — `color-codes-for`:

    :status   open=cyan, in_progress=yellow, closed=dim, else=[]
    :priority "0"=red+bold, else=[]
    :mode     [:faint]   ; uniform
    :type     [:faint]   ; uniform

**Proposed enrichment (starting point — refine in design):**

- **type:** bug=red, feature=green, task=cyan, epic=magenta, chore=faint. Unknown/custom types fall back to faint so the palette degrades gracefully on projects that override `:types`.
- **priority:** 0=red+bold, 1=yellow, 2=plain, 3=faint, 4=dim. Graded scale instead of single-value highlight.
- **mode:** afk=blue, hitl=yellow (or keep one faint and color the other — pick whichever reads better against the existing status/priority palette).

**Open design questions:**
1. Types and modes are configurable per project. Hardcode colors for the canonical defaults (`bug feature task epic chore`, `afk hitl`) and fall back to faint for custom values? Or expose a `:type-colors` map in `.knot.edn`? Recommend hardcoded defaults + faint fallback for v1 — config surface can come later if asked for.
2. Accessibility: red+green together can be hard for some users. The existing palette already mixes red, yellow, cyan; adding green for `feature` is consistent with that. `NO_COLOR`/`--no-color` already disables all of this so the plain-text path stays accessible.
3. Does the `prime` output stay monochrome? It's consumed by AI agents (per the `prime-ticket-line` docstring at `output.clj:393-404` — "whitespace-only — no ANSI codes"). Yes, keep prime monochrome; this change is for the human-facing tables only.

**Scope of fix:**
1. Extend `color-codes-for` (`output.clj:230-241`) with per-value entries for `:type`, `:priority` (graded), and `:mode`.
2. Confirm the existing `color?`/`tty?`/`NO_COLOR` gating (around lines 118-128 and 313-336) covers the new entries — should be automatic since they flow through the same `color-codes-for` indirection.
3. Update `output_test.clj` table-color tests if present; add coverage for the new mappings.
4. Eyeball against a real terminal — ANSI palettes vary by theme; pick colors that read on both light and dark backgrounds.

## Notes

**2026-05-01T03:11:26.695691072Z**

Design recommendation superseded — apply role-derivation pattern from kno-01kqg37mssy3 (status colors derived from :statuses / :active-status / :terminal-statuses, not from literal status names). Do not hardcode color literals keyed by canonical config values; that pattern has now been recognized as the recurring 'hardcoded-canonical-config-literals' bug source three times. Open question #1 (':type-colors' map in .knot.edn) becomes the right shape, possibly with role-based defaults for the canonical types/modes/priorities. See kno-01kqgqapwqvh for the broader audit.
