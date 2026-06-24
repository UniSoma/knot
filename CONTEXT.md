# Knot — Domain Context

> Glossary of domain terms used in this codebase. Filled in lazily as `/grill-with-docs` (or equivalent) resolves terminology questions during work. Empty is fine — proceed silently if nothing here applies.

## Language

### Release verification gates

**CI test**:
The `bb test` suite run by `.github/workflows/ci.yml` on push to `main` and on every PR. Covers unit and integration tests across ubuntu/macos/windows. Gates merge, not release.
_Avoid_: "the test suite" without qualifier — ambiguous with release-tag smoke.

**Pre-push smoke**:
The local single-platform check in `/release` Step 9 that the freshly-built `bbin install . --as knot-rc` binary starts and reports the expected version. Runs on the maintainer's machine before `git push --tags`. Prevention layer.

**Release-tag smoke**:
The `.github/workflows/release-smoke.yml` workflow triggered on tags matching `v*`. Installs bbin + knot from the just-pushed tag and runs a golden-path lifecycle smoke across ubuntu/macos/windows. Detection layer — a broken tag stays pushed; the signal is the Actions run status.
_Avoid_: "the CI workflow" without qualifier.

### Ticket relationships

**Parent / Children**:
A *composition* relationship — the parent is the umbrella that names a piece of work and carries its narrative; children are the pieces that belong under it. A child has exactly one parent (or none). An umbrella may have its own independent work (integration, docs, closure summary) beyond what any child delivers.
_Avoid_: "blocks" (that's `deps`), "sub-task" without specifying composition vs sequencing.

**Umbrella progress**:
The `terminal / total` tally of an umbrella's **direct** children (children-of-children are not counted). Surfaced as the `CHLD` column in listings (`list`/`ready`/`blocked`/`closed`) and as `## Children (d/t)` on `show`; emitted as `children_total`/`children_terminal` in `--json`. "Terminal" counts every closed child including `Won't do:` closures — it measures "off the umbrella's plate," not "successfully completed." Asserts nothing about readiness: an umbrella at `0/5` can still be `ready` (its own work is what's ready, not its children).
_Avoid_: "completion %", "percent done" (implies success-only and a 0–100 scale we don't render), "open-children column" (that's the gating signal ADR 0003 rejected — this is a pure rollup).

**Deps**:
A *sequencing* relationship — every id in a ticket's `:deps` must reach a terminal status before the ticket is `ready`. Missing referents count as non-terminal (broken-ref-as-blocker).
_Avoid_: "parent of", "depends on the umbrella" (umbrella ≠ blocker).

**Links**:
A loose "see also" relationship between tickets — no readiness, composition, or hierarchy semantics. Used for "related context" only.

**Ready**:
A live (non-terminal) ticket whose every `:deps` entry is in a terminal status. Computed; not a stored field. Parent/children do not affect readiness.

**Blocked**:
A live ticket with at least one non-terminal `:deps` entry.

**Referrer**:
A live or archived ticket that names another ticket's id in any of its relationship fields (`:parent`, `:deps`, `:links`). The inverse of those three fields, viewed from the referenced ticket's side. Surfaced by `knot delete <id>` as the `has_incoming_refs` envelope: a target's referrers either block the bare delete or get rewritten by `--cascade`.
_Avoid_: "incoming ref" (non-noun, splits the vocabulary), "blocker" (that's a `:deps` semantic specifically), "back-reference" (drifts toward implementation).

**Closure**:
The undirected transitive closure of one or more seed tickets over the `:parent`, `:deps`, and `:links` axes — every ticket reachable from a seed by walking any of those edges in either direction, plus the seeds themselves. Computed over the full corpus (archive included) so the membership set is graph-faithful regardless of status; per-command display filters then decide which members appear. Surfaced as `--closure <id>[,<id>…]` on `list`/`ready`/`closed`/`blocked`, with `--via parent,deps,links` to narrow the participating axes.
_Avoid_: "subgraph" (drifts toward implementation), "neighborhood" (suggests 1-hop), "related set" / "connected component" (loses the transitive-from-a-seed framing).

**Leverage**:
The number of *live* tickets that transitively depend on a given ticket through `:deps`, computed over the **live-induced** deps subgraph — the size of its directed forward unblocking cone, the ticket itself excluded. Measures how much downstream work is ultimately gated by the ticket: closing a high-leverage ticket moves the most other work toward `ready` and dissolves the most of the deps graph's "waiting" structure. A plain count over the live-induced subgraph — closed tickets are both excluded from the tally *and* non-conductive (a since-closed intermediary severs the chain, so it cannot carry waiting through itself) — not depth-, priority-, or edge-weighted. An axis *orthogonal* to readiness — it is neither a component of `ready`/`blocked` nor gated by them; a deps-leaf with a large cone is simultaneously `ready` and highest-leverage.
_Avoid_: "centrality" / "degree" (undirected, all-axes — leverage is directed and `:deps`-only), "priority" (intrinsic value; leverage is purely structural), "marginal unblock" / "immediate unblock" (the count that become `ready` the instant it closes — a different, scheduling-oriented scalar), "heatmap" (a *presentation* of leverage, not the metric itself).

**Coupling**:
For a live ticket, the count of *distinct live tickets* it is directly connected to through `:deps` (in either direction) or `:links`, computed over the **live-induced** graph — its undirected 1-hop degree over those two axes, deduped across them. Measures local entanglement: how much surrounding live context you must hold to reason about the ticket, so a high-coupling row marks where complexity concentrates in the open backlog. `:parent` is deliberately excluded (umbrella structure is organized load already carried by **Umbrella progress**, not tangle) and closed neighbors do not count (a resolved dep is not current load). An axis *orthogonal* to leverage: leverage is directed, `:deps`-only, and forward ("what work this gates"); coupling is undirected, `:deps`+`:links`, and local ("how tangled this is to think about").
_Avoid_: "centrality" / "betweenness" (path-based and opaque; coupling is a plain 1-hop degree), "articulation point" / "cut vertex" (the prescriptive cut-finder deferred to v2; coupling is the diagnostic gradient), "complexity" (overloaded with effort/estimation — coupling is purely structural), "leverage" (the orthogonal directed-`:deps` cone), "closure" (a full-corpus membership filter; coupling is a live-induced per-row count).

**Connected components**:
The partition of the **live-induced** undirected graph — over all three axes (`:parent` ∪ `:deps` ∪ `:links`) — into maximal mutually-reachable clusters, where closed tickets are non-conductive (not nodes; edges incident to them dropped, so a cluster joined only through a closed bridge splits). Names which tickets form *one body of work*: two rows in the same component are reachable from each other through live relationships. Surfaced as the leading `CC` column / `cc` JSON field, where each ≥2-member component gets a throwaway global ordinal (size-descending, largest = 1, ties by min member id) and singletons render `-` / `null`. Membership and size are *filter-independent* (computed over all live tickets, never re-partitioned per view). A whole-island grouping, distinct from coupling's per-row local degree and leverage's directed forward cone.
_Avoid_: "closure" (a full-corpus *single-seed* membership filter where closed tickets still conduct — connected components are whole-backlog, live-induced; the scope asymmetry is deliberate), "coupling" (a per-row 1-hop scalar, not a partition), "cluster id" / "stable id" (the ordinal is a within-snapshot reading aid recomputed every invocation, not an identifier), "island" as a UI term (use "component").

## Relationships

- **CI test** gates merge to `main`; **pre-push smoke** runs locally during `/release` before push; **release-tag smoke** runs post-push on tag. All three are independent gates with distinct triggers and consumers — none implies the other.
- **Parent** and **Deps** are orthogonal axes. A child may or may not also be a dep of its parent; a dep may or may not also be a child. If a child must finish before the parent's own work proceeds, it goes in the parent's `:deps` *explicitly* — composition does not imply sequencing.
- An umbrella ticket can legitimately appear in `ready` while it has open **Children**: the umbrella's own work (integration, docs, summary) is what's ready, not the children.
- **Links** never participate in readiness.

## Example dialogue

> **Dev:** "Should the v0.4 release-plan ticket be hidden from `ready` until all its children close?"
> **Maintainer:** "No — the release plan *is* its own work (writing the summary, cutting the tag). Children are what it groups, not what gates it. If a specific child has to land before I can cut the tag, that child goes in the plan ticket's `:deps`."

## Flagged ambiguities

- "parent blocks parent" — proposed and rejected (2026-05-14). Conflates composition with sequencing; loses the "umbrella has own work" and "optional follow-up child" cases. Sequencing belongs in `:deps`.
