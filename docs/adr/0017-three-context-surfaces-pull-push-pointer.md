# Three context surfaces: pull, push, pointer

Knot reaches an AI agent in a client project through exactly three surfaces, distinguished by *when* their material enters the agent's context:

- **pull** — `knot --help` and `knot <cmd> --help`. Zero context cost until fetched, one tool call away, generated from the command registry in `knot.help`, so it cannot drift from the code.
- **push** — `knot prime`, injected by the project's `SessionStart` hook. Loaded into every turn of every session whether or not the session touches a ticket. The only surface that can carry live project state.
- **pointer** — the bundled `knot` skill. Its `description` is always loaded; its body loads when the description fires.

Through v0.9.0 material accumulated on whichever surface it was written for first. The intent→command table lived on both push and pointer in near-identical copies. The skill carried a 13-line command index and a filter inventory that `--help` already generates. `## Recently Closed` spent 38% of push's words on summaries truncated mid-sentence, handing the agent half a claim. The `prime-skill-pointer` helper existed, per its own docstring, to stop the two preambles' closing sentences from drifting — and had one caller, because the other preamble had already drifted to a different sentence.

We place material by **which failure it prevents**, ranked: (a) the agent hand-edits or greps `.tickets/` and corrupts the corpus; (d) the agent never reaches the skill; (b) the agent picks the wrong command or flag; (c) the agent burns context. Only (a) is unrecoverable — a flipped `status:` line strands a file where every later query misses it. (b) is self-correcting: unknown flags are rejected loudly rather than absorbed, so a wrong guess errors instead of writing.

That ranking yields one rule per surface:

- **Pull is authoritative for anything derivable from the CLI.** No command or flag inventory appears in prose anywhere. Prose names a flag only when the point is *which flag to choose* — `--add-tag` over `--tags` because `--tags` replaces, `--mode afk` because other agents route off it. A caveat that belongs to one command goes into that command's help page (`:notes`), not into a document about it.
- **Push carries only what a cold agent cannot recover, plus live state.** The routing rule, three intent rows chosen by irrecoverability (`ready`, `show`, `close --summary`), and the state no tool-free agent can see. It stands alone for safety: a project that installed the CLI and not the skill still won't corrupt its corpus, and one `knot --help` recovers the rest.
- **Pointer carries the judgment help text can't hold** — invariants, gates, deps vs links, JSON decision logic — and nothing that either other surface already states.

`prime`'s two preambles are treated separately. The `hitl` preamble shrinks; the `afk` one keeps its full loop, because an autonomous agent's turns may contain no user utterance that ever fires the skill's description — its pointer is the weakest of the three, so its push budget is the right home for its whole sequence.

## Considered options

- **Frequency-based placement: the most-used material goes on push** — rejected. It is the intuitive rule and it produced the state we're fixing: every table grew toward the always-loaded surface because every row was arguably common. Frequency has no ceiling, so it cannot say what to *remove*; irrecoverability can, and it selects a strictly smaller set. It also mis-ranks — `knot list --type bug` is frequent and completely recoverable, `close --summary` is rare and loses information permanently.
- **Collapse to two surfaces: fold the skill into `prime`** — rejected. Superficially attractive since push reaches every session unconditionally and needs no description to fire. But it would load the gates, the JSON protocol, and the graph semantics into every turn of every session in every client project, most of which never touch a ticket. The pointer exists precisely to keep that material out of context until something asks for it.
- **Keep duplicates deliberately, so each surface reads standalone** — rejected. The argument is real for the pointer: a skill someone reads cold should make sense. But two copies of a *sequence* is the worst shape — they drift into differing orders and the agent has no way to tell which is current, which is exactly what happened to the autonomous loop. Where a surface genuinely needs standalone coverage, it gets a pointer to the owner, not a copy.
- **Generate the skill body from the command registry** — rejected for now. It would make the pointer as drift-proof as pull. But the pointer's value *is* the judgment that isn't in the registry, so generation could only ever produce the inventory we just deleted, and it would couple skill edits to a build step.

## Consequences

- `knot prime`'s `hitl` preamble drops from ~250 to ~110 words and `## Recently Closed` renders `id  title` only. `--json` still carries full summaries — JSON consumers are not a context surface and pay no attention cost.
- `truncate-prime-summary` and `prime-summary-char-cap` are deleted along with their four tests. A future request to "show a bit of the summary in prime" is a request to re-open this ADR: the fix for a bad truncation is not a smarter cut.
- `knot.help` grows a `NOTES` section for per-command caveats. This is the destination for any gotcha currently cached in prose, and the reason such caching is now unnecessary.
- `prime-skill-pointer` is deleted and both sentences inlined. A helper that failed at the drift it was named for reads as protection that isn't there; if the two must match, a test asserting the string is the mechanism, not a function.
- Push's pointer sentence and the skill's `description` keep **disjoint** branch lists. The description triggers cold — project markers, `<prefix>-01<base32>` id shape, ticket-shaped intent. Push's sentence names what push declined to cover: gates, deps vs links, `--json` decision logic, autonomous mode. Neither should grow into the other's list.
- `kno-01kzxmbta84f` (the doc/CLI flag-drift test guard) gets a smaller job: with inventories gone, the surface it must guard is the handful of flags named for their choosing distinction.
- "Leverage" stays reserved for the `CONTEXT.md` ticket metric. The three surfaces are pull, push, and pointer — recorded under `## Flagged ambiguities` so the collision doesn't come back.
