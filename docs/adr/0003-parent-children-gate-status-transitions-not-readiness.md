# `parent`/children gate status transitions, not readiness

knot tickets carry two relational fields with overlapping names but distinct semantics: `:deps` (sequencing — every referent must reach terminal status before this ticket is `ready`) and `:parent` (composition — this ticket belongs under an umbrella that names the larger piece of work). A future reader, especially one coming from trackers where "epic → story" hierarchies *do* gate work, would reasonably assume that an umbrella with open children should be hidden from `ready` and refuse to close until its children close. We deliberately do not do that.

`parent` and `:deps` are orthogonal axes. A child may or may not also appear in its parent's `:deps`. An umbrella legitimately has its own work — integration, docs, the closure summary — that lives on the parent ticket and is not owned by any single child; that work can be `ready` even while children are in flight. Some children are also follow-ups, not gates, and the umbrella ships without them by design. Conflating composition with sequencing collapses all of these into one rule and loses the "umbrella has own work" and "optional follow-up child" cases. If a particular child must finish before the parent's own work proceeds, the parent declares it in `:deps` *explicitly* — one line, no inference.

Open children do, however, gate two status transitions: `active → terminal` (the close moment, where the umbrella's narrative is sealed) and `* → active` (the start moment, where picking up the umbrella commits the maintainer to its own work). Both gates key on the transition, not on the command, so `knot close`, `knot start`, and `knot update --status …` all fire the same check — mirroring the existing `gate-acceptance!` precedent in `cli.clj`. The override is the existing `--force` flag; stderr enumerates whichever gates were bypassed (open AC titles, open child ids), and `--force --summary "<reason>"` on terminal transitions already requires a recorded reason. The JSON envelope on a fired gate is `{ok: false, error: "open-children", open-children: [...ids]}`, matching the shape used by the AC gate.

## Considered options

- **Open children block readiness** — rejected. Forces every umbrella with its own work to be split, and every optional follow-up child to be modeled as a non-`parent` relation. The win (auto-block without redoing `:deps` when children change) doesn't pay for the lost expressiveness; the explicit `:deps` line is cheap.
- **Refuse close until children close (strict sum-of-children)** — rejected. Inconsistent with the readiness model above. The maintainer's call at close time is "are we shipping the umbrella in this shape?", not "have we mechanically closed every descendant?".
- **Silent close with auto-summary appendix** — rejected. The realistic failure mode is *forgetting* a follow-up exists, not deliberately shipping without it; silent close hides the slip list at exactly the moment a human glance is most useful.
- **Per-gate force flags (`--force-ac`, `--force-open-children`)** — rejected. Adds CLI surface for a rare "surgical force" case and either silently broadens or fragments the existing `--force` semantics.
- **Require a recorded reason when forcing past open children at start** — rejected. Start is provisional (a maintainer can `update --status` back to intake at zero cost); ceremony budget should be proportional to the durability of the action. Close already requires `--summary`; start does not.

## Consequences

- Agents listing `ready --mode afk --json` may now hit `ok:false error:"open-children"` on `start`. The protocol is: read the list, recurse into a child (preferably one that's also `ready`), or pass `--force` if the umbrella's own work is what's actionable.
- `knot show` continues to render `## Children` purely informationally; no listing surface (`list`, `ready`, `blocked`, `closed`) gains an "open children" column.
- The `epic` ticket type stays dormant. Strict sum-of-children semantics for `epic` could be revisited if a real workflow ever demands them, but no current ticket needs it.
