# Listing filters and columns

Deep semantics for the listing commands (`list`/`ready`/`blocked`/`closed`,
and `prime`). `SKILL.md` covers the common attribute filters
(`--type`/`--status`/`--tag`/`--mode`/`--assignee`/`--priority`/`--limit`,
each repeatable, all five commands); this file is the on-demand reference for
the acceptance filter, the graph filters, and the computed columns.

## `--acceptance-complete=<true|false>`

Filters on completion of *structured acceptance work*, on the four listing
commands (`list`/`ready`/`blocked`/`closed`, *not* `prime`). `=false` keeps
tickets with **at least one** `:acceptance` entry still `done: false`; `=true`
keeps tickets where **every** entry is done. Tickets with no `:acceptance`
list at all are excluded from **both** views — absent criteria mean the
dimension does not apply, so `=false` and `=true` do not partition the corpus
between them. Bare `--acceptance-complete` means `=true`.

`=true` on `list` is close to `prime`'s `ready_to_close` section but not
identical: `prime` additionally requires the ticket to sit in the project's
`:active-status`, where `list --acceptance-complete=true` spans every live
status. A fully-checked ticket still in `open` shows up in the `list` view and
not in `prime`'s.

## `--assignee ""`

An empty `--assignee` value means **unassigned** on all five listing commands
(`list`/`ready`/`blocked`/`closed`/`prime`), mirroring the write side, where
`update <id> --assignee ""` clears the field. It matches tickets with no
`assignee` key and tickets whose `assignee` is blank, and it composes with
named handles in the same call: `--assignee "" --assignee alice` is "free or
alice's".

`knot ready --assignee ""` is the frontier query for an agent looking for
unclaimed work; pair it with `knot start <id> --assignee me --if-unassigned`
(see `lifecycle-gates.md`) so two agents reading that list cannot both win the
same ticket.

## Graph filters

### `--parent <id>`

Filters to the **direct children** of a parent on the four listing commands
(`list` / `ready` / `blocked` / `closed`) and on `prime`, where it scopes all
four primer sections at once — the umbrella view of a wave. It is repeatable
(children of any given parent), and its value resolves like any partial id
(live+archive) — an unresolvable value fails loudly (stderr die, or a
`not_found` / `ambiguous_id` envelope under `--json`) on the listings. `prime`
is the exception: it always exits 0, so an unresolvable value degrades to the
no-project primer instead.

### `--closure <id>[,<id>…]`

Filters to the **undirected transitive closure** of the seed(s) — every
ticket reachable from a seed by walking `:parent`, `:deps`, and `:links`
edges in *both* directions, recursively (the seed itself is included). Use
it for "everything related to this ticket," where `--parent` (1 hop,
children only) and `dep tree` (directed `:deps` only) stop short. Available
on the same four listing commands. Multi-seed is a **union** (comma-separated
or repeatable). `--via <axes>` (comma-separated subset of
`parent,deps,links`, default all three) narrows which edge types the walk
follows — e.g. `--via parent,deps` to skip the noisier `:links` axis. The
closure is computed over the full corpus (archive included) so it never halts
at a closed ticket, but each command's normal display filter still governs
what's shown (`list` defaults to live, `closed` to terminal). It composes
with every other filter (`--type`, `--status`, `--limit`, `--json`); output
is a plain list — no extra columns or JSON fields. Seeds resolve like
`--parent` (partial ids, loud failure on no/ambiguous match).

### `--component <id>`

Filters to the seed's **live-induced connected component** (the `CC`
column's action-companion: the column reveals the live islands,
`--component` isolates one to work on it). Available on `list`/`ready`/`blocked`
(NOT `closed` — the archive has no live components). It restricts the
*traversal* to live tickets (closed = non-conductive), so it matches the `CC`
column exactly — and is therefore **distinct from `--closure`**, not a live mode
of it: `--closure` is corpus-wide where a closed ticket still conducts, so a
live `A`—closed `C`—live `B` chain is one closure but two live components. Fixed
shape: a **single** id resolved by partial match like `--parent`
(**never** an ordinal — `--component 1` fails to resolve; an unresolvable or
ambiguous seed dies on stderr, or returns a `not_found` / `ambiguous_id`
envelope under `--json`), **all** axes (no `--via`), the seed **must be live**
(a closed seed is a fail-fast error, not a silent empty — note this one dies on
stderr with exit 1 even under `--json`, it is *not* an error envelope), and
**mutually exclusive with `--closure`** (passing both → fail-fast). Membership is
computed over the full live corpus and intersected before display filters, so
`--component X --tag p0` is `(X's live component) ∩ (p0-tagged)`; `--limit`
applies last. Output shape is unchanged (the `CC` column still renders the
cluster's constant ordinal; `--json` rows still carry `cc`) — it is a filter,
not a visualization. Any cluster member names the whole island, so feed a `CC`
member id straight back in.

### Composition

```
knot list --type bug --type chore
knot ready --mode afk --tag p0
knot ready --priority 0
knot blocked --mode afk
knot closed --type bug --limit 5
knot list --parent kno-01abc
knot list --closure kno-01abc --via parent,deps
knot list --component kno-01abc
knot list --acceptance-complete=false --mode afk
```

## Reading the columns

`knot help list` defines every computed column and names its `--json` field —
do not re-derive them from here. What help cannot tell you is how to *act* on
them:

- **`LEV` picks the next ticket when several are ready.** The highest-leverage
  ready ticket dissolves the most waiting structure, so it is the default
  answer to "which of these first?" — priority only overrides it when a
  deadline says so.
- **`CPL` is a cost, not a virtue.** A high-coupling ticket needs the most
  surrounding context loaded before it can be reasoned about, so it is a poor
  fit for a cold agent run and a good candidate for splitting.
- **`LEV` and `CPL` are orthogonal to readiness.** A deps-leaf can be `ready`
  and the highest-leverage row at once; neither number says anything about
  whether the ticket can be started.
- **`LVL` orders work into waves.** Everything at level 0 can run now,
  everything at level 1 becomes ready once level 0 closes, and so on — so an
  orchestrator gets its schedule with
  `knot blocked --parent <id> --json | jq 'group_by(.level)'` and needs no
  separate waves command. `LEV` says which of the ready tickets to take
  first; `LVL` says how many rounds away the rest are.
- **A `-` in `LVL` is a bug report, not a big number.** It means the ticket
  is on a live deps cycle, so no schedule exists until the cycle is broken —
  run `knot check` and cut an edge.
- **`CHLD` is progress, not readiness.** An umbrella at `0/5` may still be
  `ready` — its own integration work is what is ready, not its children.
- **Component membership ignores your filters**, so a `CC` ordinal read off a
  filtered view still names the whole island. Feed any member id to
  `--component` to see the rest of it.
