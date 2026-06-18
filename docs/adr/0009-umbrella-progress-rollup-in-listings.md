# Umbrella progress rollup in listings (not a gate)

Listings (`list`, `ready`, `blocked`, `closed`) and `show` now surface an umbrella's **progress** — the `terminal / total` tally of its **direct** children — as a `CHLD` column (and `## Children (d/t)` on `show`, plus `children_total`/`children_terminal` in `--json`). This reverses one Consequences line of [0003](0003-parent-children-gate-status-transitions-not-readiness.md), which stated "no listing surface … gains an 'open children' column." That line is superseded as of 2026-06-18; the rest of 0003 — composition (`:parent`) does not gate readiness, and the start/close transition gates — stands unchanged.

The reversal is narrow and deliberate. 0003 rejected surfacing a **gating** signal in lists: an "open children" marker reads as "this umbrella is blocked," which would smuggle composition back into the readiness axis 0003 worked to keep separate. A **progress rollup** carries no such claim. `CHLD 0/5` and `CHLD 5/5` are both fully compatible with the row also being `ready` — the column reports how much of the grouped work has reached terminal status, nothing about whether the umbrella's *own* work can proceed. We surface the rollup precisely because it is orthogonal to readiness, the same orthogonality 0003 established.

"Terminal" reuses the existing `terminal-statuses` set (the same set the AC gate and the open-children gate key on), so a `Won't do:` child counts toward the numerator: the metric is "off the umbrella's plate," not "successfully shipped." Distinguishing abandoned from completed is detail for the per-child `## Children` list, not the one-cell tally. The denominator is direct children only — each ticket reports its own immediate rollup; a child that is itself an umbrella shows its own tally on its own row, so a deep hierarchy is still fully legible without recursive aggregation in a single cell.

The column follows the `AC` column's mechanics exactly: rendered `d/t`, **conditionally spliced** into the table only when at least one ticket in the result set is an umbrella, and invisible otherwise. This is what lets the reversal stay faithful to 0003's underlying instinct — quiet projects with no umbrellas see no new column at all. Umbrella-ness is computed from the inverse of `:parent` over the full corpus (live + archive), never from the `epic` type, which stays dormant per 0003 and the glossary.

## Considered options

- **Amend 0003 in place instead of a new ADR** — rejected. 0003's reasoning about gating is correct and should stay legible as written; the rollup-vs-gate distinction is new reasoning that deserves its own record rather than being buried as an edit to an old one. 0003's superseded line is cross-linked here instead.
- **Recursive descendant rollup in the column** — rejected for the list/`show` cell. Costlier, and it collapses a multi-level hierarchy into one number that hides where the work actually sits. Direct-children rollup keeps each row honest; a recursive tree view, if ever wanted, is a separate `show`-side feature.
- **`closed-done / total` (exclude `Won't do:` from the numerator)** — rejected for the one-cell tally. The list answers "how far along," and an abandoned child is resolved, not pending. Success-vs-abandoned lives in the per-child status list.
- **Percent or glyph bar instead of `d/t`** — rejected. Percent drops the "how big is this umbrella" signal; a bar needs width budget list rows don't have. `d/t` mirrors the `AC` column, sorts and greps cleanly, and needs no color or terminal-width support.
- **Behind an opt-in flag** — rejected. The conditional splice already makes the column self-revealing and zero-cost in umbrella-free projects, so a flag adds CLI surface for no gain.
- **`children` nested object in list `--json`** — rejected. The key `children` already names the `[{id,title,status},…]` array in `show --json`; reusing it for a `{total,terminal}` object across two commands is a footgun. Flat `children_total`/`children_terminal` snake_case fields, present only on umbrella rows, sidestep the collision and double as the "is this an umbrella" predicate (`has("children_total")`).

## Consequences

- The `CHLD` column appears across `list`/`ready`/`blocked`/`closed` whenever the result set contains ≥1 umbrella; non-umbrella rows render `-`.
- `show` gains `## Children (d/t)` on the human view and `children_total`/`children_terminal` on `--json`; the per-child `## Children` list is unchanged.
- Agents consuming `ready --json` can now read umbrella progress directly rather than re-deriving it from the corpus (which a single list entry never permitted).
- 0003's "no 'open children' column" consequence is superseded; its readiness and transition-gate decisions are not.
- The `epic` ticket type remains dormant — umbrella-ness is still conferred solely by `:parent`.
