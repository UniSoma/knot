# Knot — Domain Context

> Glossary of domain terms used in this codebase. Filled in lazily as `/grill-with-docs` (or equivalent) resolves terminology questions during work. Empty is fine — proceed silently if nothing here applies.

## Language

### Ticket relationships

**Parent / Children**:
A *composition* relationship — the parent is the umbrella that names a piece of work and carries its narrative; children are the pieces that belong under it. A child has exactly one parent (or none). An umbrella may have its own independent work (integration, docs, closure summary) beyond what any child delivers.
_Avoid_: "blocks" (that's `deps`), "sub-task" without specifying composition vs sequencing.

**Deps**:
A *sequencing* relationship — every id in a ticket's `:deps` must reach a terminal status before the ticket is `ready`. Missing referents count as non-terminal (broken-ref-as-blocker).
_Avoid_: "parent of", "depends on the umbrella" (umbrella ≠ blocker).

**Links**:
A loose "see also" relationship between tickets — no readiness, composition, or hierarchy semantics. Used for "related context" only.

**Ready**:
A live (non-terminal) ticket whose every `:deps` entry is in a terminal status. Computed; not a stored field. Parent/children do not affect readiness.

**Blocked**:
A live ticket with at least one non-terminal `:deps` entry.

## Relationships

- **Parent** and **Deps** are orthogonal axes. A child may or may not also be a dep of its parent; a dep may or may not also be a child. If a child must finish before the parent's own work proceeds, it goes in the parent's `:deps` *explicitly* — composition does not imply sequencing.
- An umbrella ticket can legitimately appear in `ready` while it has open **Children**: the umbrella's own work (integration, docs, summary) is what's ready, not the children.
- **Links** never participate in readiness.

## Example dialogue

> **Dev:** "Should the v0.4 release-plan ticket be hidden from `ready` until all its children close?"
> **Maintainer:** "No — the release plan *is* its own work (writing the summary, cutting the tag). Children are what it groups, not what gates it. If a specific child has to land before I can cut the tag, that child goes in the plan ticket's `:deps`."

## Flagged ambiguities

- "parent blocks parent" — proposed and rejected (2026-05-14). Conflates composition with sequencing; loses the "umbrella has own work" and "optional follow-up child" cases. Sequencing belongs in `:deps`.
