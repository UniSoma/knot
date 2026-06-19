# Closure filter on listings (one flag, four commands)

The four listing commands (`list`, `ready`, `blocked`, `closed`) gain `--closure <id>[,<id>…]` (with `--via parent,deps,links` to narrow axes) — a per-row membership predicate that admits tickets in the undirected transitive **closure** of the seed(s) over `:parent`, `:deps`, and `:links`. The closure is computed on the full corpus (archive included); each command's normal display filter then decides which members appear. The flag is a filter, not a visualization: rows render as plain list rows, and `--json` shape is unchanged.

The decision orients around an existing surface. `--parent <id>` already filters lists by a 1-hop relationship and carries the partial-id-resolution machinery (`resolve-parent-filter!` in `main.clj`); `dep tree <id>` already visualizes the directed-out `:deps` walk. The gap is a *set-shaped* answer to "everything related to this seed" that composes with the rest of `list`'s flags (`--type`, `--status`, `--tag`, `--limit`, `--json`). `--closure` fills that gap exactly — closure-set is computed once per invocation on the full corpus, then `query/filter-tickets` runs on the intersection in the existing pipeline. No new command, no new output shape, no new error envelopes.

Undirected and transitive are deliberate defaults. The user's framing was "all tickets related to it (parent, children, linked, dep etc)"; both halves matter. Directed-out would have duplicated `dep tree`; 1-hop would have duplicated `--parent` and the relationship lists `show` already prints. Closure-as-set is the new capability, and undirected is the only consistent choice across the three axes (`:links` is already symmetric, so a directed model would have to special-case it). Multi-seed is union semantics — `closure(id1) ∪ closure(id2)` — to match the OR-set conventions of every other list filter (`--parent id1,id2`, `--tag a,b`, `--type bug,feature`).

`--via` is the one shape concession: `:links` is the noisiest axis (CONTEXT.md: "no readiness, composition, or hierarchy semantics — used for 'related context' only"), so users get an opt-out path without us guessing for them. One restriction flag is cheaper than three negative flags (`--no-links`, …) and avoids "what if I pass conflicting flags" arithmetic.

## Considered options

- **A standalone `knot closure <id>` command instead of a flag** — rejected. The user asked for *filtering*, not exploration, and a dedicated command would have to re-implement `--type`/`--status`/`--tag`/`--limit`/`--json` composition. Exploration is already covered: `knot show` lists the seed's edges and referrers; `knot dep tree` walks the directed `:deps` subgraph.
- **1-hop neighborhood by default (transitive opt-in)** — rejected. The feature is *named* closure; 1-hop is mostly already covered by `--parent <id>` and `show`. The richer set is what's new.
- **Directed walk (outgoing or incoming only)** — rejected as the default. Outgoing-`:deps` duplicates `dep tree`; outgoing-only across axes is hard to explain consistently with symmetric `:links`. If a directed variant is ever needed, it's a follow-up flag.
- **`:parent` + `:deps` only by default; `:links` opt-in** — rejected. Defies the user's literal request, and `:links` exclusion is one `--via parent,deps` away.
- **`VIA` / `DIST` column annotating each row** — rejected. Multi-path tickets (e.g. both a child *and* a dep) force an arbitrary tie-break; multi-seed forces "which seed am I explaining the connection to?"; the table is already dense and narrow-terminal TITLE truncation is the main UX cost on lists. Filter, not explorer.
- **Live-only graph traversal** — rejected. Halting at archived nodes violates closure properties (a closed child blocks the walk from reaching its open grandchildren). Membership is computed on the full corpus; display is governed by each command's normal status filter, so `list --closure X` still defaults to live, `closed --closure X` still defaults to terminal, etc.
- **Warning on isolated seeds or partial multi-seed resolution** — rejected. Empty result is a true result, not an error; fail-fast on the first unresolvable seed (matches `--parent`) is enough.
- **Storing closure metadata in `--json`** (e.g. `closure_via`, `closure_distance` per row) — rejected. Pollutes a stable shape that consumers already depend on; the question it answers (*why* is this row here?) belongs in `show`, not in a filter's output.
- **Intersection semantics for multi-seed** — deferred, not killed. Union is what every other list-filter convention implies; intersection is exotic enough to wait for an actual ask.

## Consequences

- The four listing commands gain `--closure <id>[,<id>…]` and `--via <axes>`. Other commands (`show`, `dep tree`, `delete`, `check`) are untouched.
- A new primitive `query/closure-set` lands alongside `dep-tree`/`project-cycles`/`inverses`. Listing handlers apply it before `filter-tickets`.
- The seed itself is in the closure set; whether it appears in the rendered list depends on the command's normal status filter (an archived seed is hidden by `list` but visible to `closed`).
- Broken refs and cycles are handled by the same conventions as `dep-tree`/`project-cycles`: cycles guarded by visited-set, missing referents silently dropped from membership.
- `CONTEXT.md` gains a **Closure** glossary entry under "Ticket relationships," with `_Avoid_` guidance steering away from "subgraph" / "neighborhood" / "related set."
- A future directed or depth-bounded variant remains additive — `--closure-out`, `--closure-depth N` would extend the same flag family without revisiting this ADR.
