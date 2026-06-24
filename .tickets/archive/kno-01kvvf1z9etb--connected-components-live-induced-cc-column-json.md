---
id: kno-01kvvf1z9etb
title: 'Connected components: live-induced CC column + JSON on list/ready/blocked'
status: closed
type: task
priority: 2
mode: afk
created: '2026-06-24T00:04:09.133908513Z'
updated: '2026-06-24T00:29:45.639733516Z'
closed: '2026-06-24T00:29:45.639733516Z'
acceptance:
- title: knot list shows a leading left-aligned CC column when ≥1 visible row is in a multi-member live component; column vanishes when all visible rows are singletons
  done: true
- title: CC ordinals are size-descending over all live multi-member components (largest = 1), ties broken by min member id, numbered globally and filter-independently; singletons render -
  done: true
- title: Component membership is live-induced over :parent ∪ :deps ∪ :links and unaffected by --tag/--type/--limit filters
  done: true
- title: CC renders identically on ready and blocked
  done: true
- title: --json emits cc on every list/ready/blocked row (integer ordinal, null for singletons) and is absent on show/closed/touched
  done: true
- title: show, closed, and touched-mutator outputs stay byte-unchanged (no CC column, no cc field)
  done: true
- title: ADR 0013 added documenting the column, the --closure asymmetry, and the ordinal contract; SKILL.md updated same commit
  done: true
- title: bb test green and clj-kondo --lint src test clean
  done: true
---

## Description

## What to build

A new leading **CC** column on `list`/`ready`/`blocked` that marks which connected component each ticket belongs to in the **live-induced** graph, so clusters are identifiable at a glance. Mirrors the LEV/CPL derived-column family (ADRs 0011/0012), with its own `query` partition primitive.

### Contract decisions (from design grill)

- **Graph scope — live-induced.** Membership flows only through live tickets (ready ∪ blocked); closed tickets are non-conductive, exactly like LEV/CPL. (Deliberately diverges from `--closure`, which is corpus-wide — document the asymmetry.)
- **Edge axes — all three**, undirected: `:parent` ∪ `:deps` ∪ `:links`. Same axis set as `--closure`; the only difference is live-induced vs corpus-wide. Accepts epic-driven mega-blobs.
- **Membership + member count are filter-independent.** Computed over *all* live tickets regardless of `--tag`/`--type`/`--limit` (same as `annotate-leverage all …`). A ticket's component and size never change with the view.
- **Label — throwaway global ordinal.** Only components with **≥2 live members** get a number; **singletons render `-`**. Ordinal assigned **size-descending** (largest live component = `1`), ties broken by **min member id**. Numbering is **global** over all live multi-member components — a filtered view may show non-contiguous numbers (`1, 3, 4`); no per-view renumbering.
- **Presence-gating (text)** — column **vanishes** when every visible row is a singleton (shown iff ≥1 visible row carries a real ordinal). This is stricter than LEV/CPL's key-presence gate, deliberately, to avoid an all-`-` column.
- **JSON** — `cc` emitted on **every** list/ready/blocked row: integer ordinal, **`null` for singletons** (uniform shape so consumers never branch on key-presence, matching how `leverage`/`coupling` always appear). Absent on `show`/`closed`/touched.
- **Color** — none in v1 (plain digit, like LEV/CPL).
- **Placement & style** — **first column**, before ID. Header `CC`, **left-aligned**. Layout: `CC  ID  STATUS  PRI  MODE  TYPE  ASSIGNEE  AGE  [AC] [CHLD] [LEV] [CPL]  TITLE`.

### Implementation surface

- `query.clj`: new live-induced partition primitive (flood live graph over the three axes → per-ticket component) + size-desc global ordinal assignment. `closure-set` stays untouched (corpus-wide single-seed for the `--closure` filter).
- `cli.clj`: `annotate-cc` attaches `:cc` to rows on list/ready/blocked, computed against the full live corpus.
- `output.clj`: `ls-cc-column`; `ls-columns-for` **prepends** CC (not in the before-TITLE extra group), gated on a visible numbered row; `value-of` `:cc` case (`nil`/singleton → `-`); `jsonify-ticket` emits `cc`.

### Deliverables (repo hard rules)

- **ADR 0013** documenting the CC column + the live-induced-vs-corpus asymmetry with `--closure` + the throwaway-global-ordinal contract.
- **`.claude/skills/knot/SKILL.md`** updated in the same commit (new column + `cc` JSON field on list/ready/blocked).
- `bb test` green + `clj-kondo --lint src test` clean before commit.

## Notes

**2026-06-24T00:29:45.639733516Z**

Implemented the leading CC column + cc JSON field on list/ready/blocked per ADR 0013. New query/connected-components primitive: live-induced undirected partition over :parent∪:deps∪:links (closed non-conductive), size-descending global ordinals (largest=1, ties by min member id) for ≥2-member components, singletons render -/null. Filter-independent membership; stricter presence-gate than LEV/CPL (column vanishes when all visible rows are singletons); cc always emitted in JSON (int/null). show/closed/touched byte-unchanged.
