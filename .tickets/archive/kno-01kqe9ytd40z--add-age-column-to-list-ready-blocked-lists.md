---
id: kno-01kqe9ytd40z
title: Add age column to list/ready/blocked lists
status: closed
type: task
priority: 2
mode: afk
created: '2026-04-30T04:22:08.036135912Z'
updated: '2026-05-14T02:28:38.788737542Z'
closed: '2026-05-14T02:28:38.788737542Z'
links:
- kno-01kqgqa1jj1s
- kno-01kqys929mdy
tags:
- v0.5
parent: kno-01krhwcy0zdy
acceptance:
- title: list / ready / blocked / closed text output renders an AGE column immediately to the left of AC (or TITLE when no AC column is shown)
  done: true
- title: AGE renders Nd (<14d), Nw (14-42d), Nm (>42d), or '-' when :updated is missing/unparseable, using the existing format-age-days bucketing
  done: true
- title: AGE is computed from each ticket's :updated against now, matching prime's In Progress semantics
  done: true
- title: JSON payloads (--json) for list / ready / blocked / closed add no new fields and bump no schema_version; :updated remains the sole age-related field
  done: true
- title: The per-ticket day-count injection (:prime-age-days) is renamed to :age-days and fed by a single shared helper used by all five listing pipelines (prime included); existing prime tests updated accordingly
  done: true
- title: emacs/knot.el tabulated-list-format includes an 'Age' column between 'Assignee' and 'AC'
  done: true
- title: The Emacs Age cell is computed client-side from the JSON :updated field and renders identically to the CLI; missing/unparseable :updated renders '-'
  done: true
- title: The Emacs Age column sort key aliases to 'updated in knot-list--column->sort-key, so sorting by the column uses the underlying ISO timestamp rather than the rendered string
  done: true
- title: 'New tests cover: AGE column presence on each of the four listing commands; ''-'' fallback when :updated is missing; column position relative to AC and TITLE; the renamed :age-days key is read by every listing pipeline'
  done: true
- title: CHANGELOG.md [Unreleased]/Added entry describes the AGE column on list / ready / blocked / closed and the Emacs Age column
  done: true
- title: .claude/skills/knot/SKILL.md and emacs/README.md are synced if they document the column layout; otherwise left alone (per the AGENTS.md keep-skill-in-sync rule)
  done: true
- title: bb test passes; clj-kondo --lint src test preserves the existing baseline
  done: true
---

## Description

Extend the AGE column from `knot prime`'s `## In Progress` section to the four listing commands (`list / ready / blocked / closed`) plus `emacs/knot.el`'s list buffer. The bucketing rule (`format-age-days` — `Nd / Nw / Nm`, `-` when missing), the source field (`:updated`), and the day-delta helper already exist on `prime`; this slice extracts them for shared use.

## Settled design

- **Source:** `:updated` (matches prime). One mental model, one bucketing rule, one column meaning across the family. Touching a ticket resets the clock.
- **Scope:** `list / ready / blocked / closed` only. `prime`'s `## Ready` and `## Recently Closed` sections are out of scope — those want different semantics (creation- vs close-anchored) and belong in a separate ticket.
- **Slot:** AGE sits immediately before AC (when AC is shown) and before TITLE on every listing — same as prime's In Progress row shape.
- **JSON:** unchanged. No new fields, no schema_version bump. `:updated` is already in the payload; consumers compute age client-side. The `"stale": true` flag stays scoped to `prime`'s in-progress entries.
- **Stale text styling:** none in this slice. AGE renders bare (`Nd / Nw / Nm`); coloring the cell at ≥14d is deferred to `kno-01kqdaxz86nv`.
- **Helper rename:** the per-ticket day-count currently injected as `:prime-age-days` (cli pipeline) becomes a neutral `:age-days`, fed by a single shared helper used by all five listing pipelines (`prime` included).

## Emacs (emacs/knot.el)

- Splice an `Age` column into the list buffer's `tabulated-list-format` between `Assignee` and `AC` (Title case `Age` to match neighbours).
- Age is computed client-side from the JSON `:updated` field (the CLI ships no derived value — see "JSON" above). A pure elisp port of the bucketing rule renders the cell so output matches the CLI exactly.
- Make `Age` sortable, but alias the header to the `updated` sort key in `knot-list--column->sort-key`. Default tabulated-list string-sort on rendered cells would order `1m < 1w < 2w` — wrong; sorting by the underlying ISO timestamp produces correct order.
- Update `emacs/README.md` if it documents the column layout.

## Settled design

- **Source:** `:updated` (matches prime). One mental model, one bucketing rule, one column meaning across the family. Touching a ticket resets the clock.
- **Scope:** `list / ready / blocked / closed` only. `prime`'s `## Ready` and `## Recently Closed` sections are out of scope — those want different semantics (creation- vs close-anchored) and belong in a separate ticket.
- **Slot:** AGE sits immediately before AC (when AC is shown) and before TITLE on every listing — same as prime's In Progress row shape.
- **JSON:** unchanged. No new fields, no schema_version bump. `:updated` is already in the payload; consumers compute age client-side. The `\"stale\": true` flag stays scoped to `prime`'s in-progress entries.
- **Stale text styling:** none in this slice. AGE renders bare (`Nd / Nw / Nm`); coloring the cell at ≥14d is deferred to \`kno-01kqdaxz86nv\`.
- **Helper rename:** the per-ticket day-count currently injected as `:prime-age-days` (cli pipeline) becomes a neutral `:age-days`, fed by a single shared helper used by all five listing pipelines (`prime` included).

## Emacs (emacs/knot.el)

- Splice an `Age` column into the list buffer's `tabulated-list-format` between `Assignee` and `AC` (Title case `Age` to match neighbours).
- Age is computed client-side from the JSON `:updated` field (the CLI ships no derived value — see \"JSON\" above). A pure elisp port of the bucketing rule renders the cell so output matches the CLI exactly.
- Make `Age` sortable, but alias the header to the `updated` sort key in `knot-list--column->sort-key`. Default tabulated-list string-sort on rendered cells would order `1m < 1w < 2w` — wrong; sorting by the underlying ISO timestamp produces correct order.
- Update `emacs/README.md` if it documents the column layout.

## Notes

**2026-05-14T01:43:58.158312324Z**

> *This was generated by AI during triage.*

Triaged to `ready-for-agent` (mode `afk`). Five design knobs were settled in a grilling session:

1. **Source field:** `:updated` (matches prime).
2. **Scope:** `list / ready / blocked / closed` only — prime's Ready/Recently Closed sections deferred.
3. **Column slot:** AGE before AC, before TITLE.
4. **JSON shape:** unchanged — no new fields, no schema_version bump.
5. **Stale text styling:** none in this slice — coloring deferred to kno-01kqdaxz86nv.

Scope expanded mid-triage to include `emacs/knot.el` (Age column on the tabulated list buffer + sort-key alias to `'updated`). Full spec lives in the Description; 12 AC items in frontmatter.

**2026-05-14T02:28:38.788737542Z**

Shipped at src/knot/cli.clj, src/knot/output.clj, emacs/knot.el, .claude/skills/knot/SKILL.md, CHANGELOG.md (commit c1e6533). AGE column added to knot list / ready / blocked / closed and to emacs/knot.el's tabulated-list buffer, sourced from each ticket's :updated against ctx :now, bucketed via the existing format-age-days (Nd / Nw / Nm / -). The prime-only :prime-age-days injection was renamed to a neutral :age-days and is now fed by a single shared annotate-age-days helper called from all five listing pipelines (prime included); age helpers (parse-instant-ms / age-days-from-updated / stale-in-progress?) were hoisted above ls-cmd. JSON payloads unchanged — :age-days lives on the in-memory map and never crosses the output/jsonify-ticket projection boundary. Emacs port (knot-list--age-days / knot-list--format-age-days / knot-list--age-cell) renders identically to the CLI across edge cases (0d / 13d / 14d / 42d / 43d / nil / negative); column-header sort on Age aliases to 'updated in knot-list--column->sort-key to avoid the 1m < 1w < 2w lexical-ordering bug. 355 tests / 4450 assertions pass; clj-kondo baseline preserved (3 errors / 4 warnings, all pre-existing). All 12 ACs met; code-reviewer subagent verdict: ready to merge, no critical or important issues.
