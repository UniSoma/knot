---
id: kno-01kvdybmymby
title: knot.el — surface umbrella progress rollup (CHLD column + show "Children (d/t)" heading)
status: closed
type: task
priority: 2
mode: afk
created: '2026-06-18T18:02:12.819988445Z'
updated: '2026-06-18T18:16:01.540263560Z'
closed: '2026-06-18T18:16:01.540263560Z'
tags:
- emacs
acceptance:
- title: CHLD column appears in knot-list after AC; umbrella rows render terminal/total, non-umbrella rows render -; S on the column sorts
  done: true
- title: 'knot-show on an umbrella renders ## Children (d/t) with the rollup; per-child list unchanged; RET/+ on that section stay inert'
  done: true
- title: Both surfaces read children_total/children_terminal from --json (no client-side derivation)
  done: true
- title: bb lint:elisp is clean (byte-compile warnings-as-errors + package-lint)
  done: true
- title: 'Manually verified against a scratch umbrella: cases 0/N, N/N, and a Won''t do: child counting toward the numerator'
  done: true
links:
- kno-01kvdrjb5fwa
---

## Description

The CLI feature *Umbrella progress rollup* shipped (commit 3378e63, ADR docs/adr/0009), but emacs/knot.el was never touched. The Emacs client reconstructs its views from --json rather than echoing CLI text, so the rollup must be rendered in elisp.

Contract already live in the CLI:
- list/ready/blocked/closed --json rows carry children_total/children_terminal (integers) only on umbrella rows (>=1 direct child); absence is the umbrella predicate.
- show --json data carries those two fields and the children inverse array ({id,title,status} per child).
- "terminal" spans live+archive and counts Won't do: closures.

Affected surfaces:
- List view: tabulated-list-format vector emacs/knot.el:996; row builder knot-list--row 1222-1231; AC-cell precedent knot-list--ac-cell 1233-1241.
- Show view: let* bindings emacs/knot.el:1959; Children call site 2017; helper knot-show--render-relationship 1841-1876.

## Design

Both surfaces, one change. Use the scalar fields children_total/children_terminal as the single source of truth on BOTH surfaces (do not locally derive from the children array) — matches the CLI contract.

List view:
- Add ("CHLD" 5 t) to the static tabulated-list-format vector at :996, immediately AFTER ("AC" 5 t), before Title. Always-on (renders - for non-umbrella rows), mirroring AC's unconditional presence. Do NOT make the column conditional. Do NOT touch the AC column.
- New helper knot-list--chld-cell mirroring knot-list--ac-cell: read pre-computed children_terminal/children_total off the row, (format "%d/%d" terminal total); fall back to "-" when children_total absent. Asymmetry vs AC: reads server fields, does NOT count an array client-side.
- Render order is terminal/total (e.g. 1/2), matching AC's done/total.
- Add chld-cell to the knot-list--row vector (1222-1231) right after ac-cell.
- Sort: mirror AC exactly — column flag t so built-in S works (lexicographic string compare on rendered cell). Do NOT add to knot-list--column->sort-key, the transient sort-key set, or add a comparator.

Show view:
- At the call site :2017, build the label conditionally: (format "Children (%d/%d)" terminal total) when children_total is present on data, else plain "Children". Bind children_total/children_terminal from data in the let* at :1959.
- knot-show--render-relationship stays UNTOUCHED (it interpolates the label into ## %s). Keep passing NO section-sym for Children — stays read-only/display-only, no knot-section property, RET/+ on that section stay inert. Per-child list unchanged.

Not in scope: emacs/README.md change (it doesn't enumerate columns); elisp ERT test infrastructure (intentionally skipped); making Children an editable section; a semantic CHLD sort key; touching AC.

Constraints: no AI attribution in commits/trailers. knot.el changes do NOT require a SKILL.md update (that documents the CLI, already done). knot.elc is untracked — nothing to regenerate.

## Notes

**2026-06-18T18:16:01.540263560Z**

knot.el now surfaces the umbrella rollup: always-on CHLD column (knot-list--chld-cell reads children_terminal/children_total scalars, renders terminal/total or -, S sorts) and show heading 'Children (t/total)' on umbrellas via conditional label at the render-relationship call site. Both surfaces use server scalars, no client derivation (ADR 0009). bb lint:elisp clean; batch-verified cell + label across 0/N, N/N, absent-terminal, non-umbrella, and Won't-do-counts-terminal cases against a scratch umbrella.
