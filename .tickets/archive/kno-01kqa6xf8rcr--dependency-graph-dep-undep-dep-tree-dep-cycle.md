---
id: kno-01kqa6xf8rcr
title: Dependency graph - dep, undep, dep tree, dep cycle, ready, blocked
status: closed
type: task
priority: 2
mode: afk
created: '2026-04-28T14:12:00.407986616Z'
updated: '2026-04-28T14:32:04.960171696Z'
closed: '2026-04-28T14:12:01.279348816Z'
parent: kno-01kqa804gmgx
external_refs:
- docs/prd/knot-v0.md
- issues/0005-dependency-graph.md
deps:
- kno-01kqa6xf6gtf
- kno-01kqa6xf7mde
---
## Parent document

`docs/prd/knot-v0.md`

## What to build

The dependency-graph layer in `knot.query` plus the commands that consume it: `dep`/`undep` to add/remove edges, `dep tree <id>` to render an ASCII tree, `dep cycle` for project-wide DFS scanning, and the `ready`/`blocked` query commands. Cycle detection runs on every `dep` add and rejects with the offending path. Broken references (`:deps` or `:parent` pointing to a missing ticket) render with a `[missing]` marker plus a stderr warning, never abort.

## User stories covered

- 9 (`knot ready` lists open/in-progress tickets whose dependencies are all closed)
- 10 (`knot blocked` lists tickets whose dependencies are open)
- 11 (`knot dep tree <id>` renders ASCII dependency tree)
- 12 (`knot dep tree <id> --full` shows duplicate branches in full)
- 13 (adding a dependency fails if it would create a cycle)
- 14 (`knot dep cycle` scans for any pre-existing cycles in open tickets)
- 24 (broken `:parent`/`:deps` references render with `[missing]` and stderr warning, do not abort)

## Acceptance criteria

- [x] `knot dep <from> <to>` adds `to` to the `:deps` array of `from`
- [x] `knot undep <from> <to>` removes `to` from `from`'s `:deps`
- [x] Cycle detection runs on every `dep` add (DFS from the new dep target looking for the source); rejects with the offending path printed to stderr; exit 1
- [x] `knot dep tree <id>` renders an ASCII tree using box-drawing characters; dedupes already-seen branches with `↑` markers by default
- [x] `knot dep tree <id> --full` shows duplicate branches in full (parity with the deduped form)
- [x] `knot dep tree --json` emits a nested map (no envelope wrapping)
- [x] `knot dep cycle` performs project-wide DFS over open tickets, prints any cycles found to stderr, exits 1 if any cycle found else 0
- [x] `knot ready` lists open or in-progress tickets whose `:deps` are all in terminal status; sorted by priority asc then `:created` desc
- [x] `knot blocked` lists tickets with at least one non-terminal `:deps` entry
- [x] Both `ready` and `blocked` support `--json`
- [x] `knot show <id>` does NOT yet render computed inverse sections (deferred to slice 6) but does display the raw `:deps`/`:parent` fields
- [x] Broken `:deps` or `:parent` references (pointing to a missing ticket) render with `[missing]` marker in human output; emit a stderr warning; never abort
- [x] `knot.query` is a pure namespace over a sequence of tickets — no I/O — to keep it Pathom3-feedable
- [x] Tests: cycle detection (positive: self-loops, multi-cycles; negative: legal DAGs), `ready`/`blocked` partitioning under various dep states, `dep tree` dedup with `↑` markers and `--full` parity, broken-reference warning emission

## Blocked by

- issue-0003 (`issues/0003-lifecycle-transitions-and-archive.md`)
- issue-0004 (`issues/0004-config-and-init.md`)

## Implementation notes

### `knot.query` API surface

Six new public functions, all pure (a tickets seq in, data out) per the Pathom3-feedable constraint:

- `would-create-cycle? <tickets> <from> <to>` — returns the offending path `[from ... from]` when adding the edge would close a cycle, else `nil`. Self-loops short-circuit to `[from to]`.
- `project-cycles <tickets>` — returns a vector of cycle paths via white/gray/black DFS. Self-loops appear as `[id id]`.
- `ready <tickets> <terminal-statuses>` — non-terminal tickets whose every `:deps` entry resolves to a terminal-status ticket. Sorted by priority asc, then `:created` desc.
- `blocked <tickets> <terminal-statuses>` — non-terminal tickets with at least one non-terminal (or missing) dep. Same sort order.
- `dep-tree <tickets> <root-id> {:full?}` — nested data structure rooted at `root-id`. Default mode dedupes; `:full?` expands duplicates.
- `broken-refs <ticket> <tickets>` — typed descriptors `{:kind :deps|:parent :id}` for refs not present in the tickets seq.

The pre-existing `non-terminal` is still public; nothing was renamed or moved.

### Cycle detection: two algorithms, one for adds and one for scans

`would-create-cycle?` is a targeted depth-first search from `to` looking for `from` — the only path that could close a cycle when adding `from → to`. The visited-set guards against pre-existing cycles in the input graph so the walk terminates regardless of input shape. Self-loops short-circuit before the search.

`project-cycles` is a full traversal: iterative DFS with an explicit `[node deps-iterator path]` stack and white/gray/black coloring. A back-edge to a `:gray` node yields a cycle; the cycle path is reconstructed from the live stack via `(.indexOf path child)`. The iterative form was chosen over recursion to avoid SCI stack-depth surprises on large graphs in babashka.

### Missing refs are never cycles, but they *are* blockers

Broken references (a `:deps` entry pointing to a non-existent ticket) are treated as having no outgoing edges for cycle detection — `find-path` falls through `(deps-of (get index node))` returning `[]` for missing nodes — so a broken ref cannot manufacture a phantom cycle. Symmetrically, `ready` requires every dep to *resolve* to a terminal-status ticket, which means a broken ref blocks readiness, and `blocked` includes tickets with broken refs. This matches the PRD's broken-ref-as-blocker semantics: tooling stays lenient (no abort) but the user still sees the broken-ref ticket in `blocked` until they fix it.

### `dep cycle` filters to non-terminal tickets

`cli/dep-cycle-cmd` runs `query/non-terminal` before passing the seq to `query/project-cycles`. A cycle wholly contained within already-archived tickets generates no output and exit 0 — matching the AC ("project-wide DFS over open tickets") and avoiding noise on legacy projects with hand-edited closed tickets that happen to form a closed cycle. Cycles that span live and archived tickets surface only their live nodes.

### `dep` cycle rejection: `ex-info` with structured data

`cli/dep-cmd` throws `(ex-info "cycle detected: a → b → a" {:cycle ["a" "b" "a"]})` when the would-be edge would close a cycle. The message intentionally does *not* include the `knot dep:` prefix — `main/edge-handler` adds it via `(die (str "knot " cmd-name ": " (.getMessage e)))`. The handler peeks at `(:cycle (ex-data e))` to discriminate cycle-rejection from other exceptions and re-throws anything it does not recognize, preserving the catch-all in `main/-main`.

### `dep` is idempotent on the deps list

Adding an already-present dep skips the cycle check (no graph change to validate) and skips the `conj` (no duplicates). The save still runs so `:updated` bumps — this matches the PRD's "every Knot-mediated change bumps `:updated`" rule and keeps the command predictable as an intent signal.

### `undep` drops `:deps` when empty

When the last entry is removed, `:deps` is `dissoc`'d entirely rather than written as `[]`. This keeps the on-disk frontmatter clean (no empty arrays), which matters because `clj-yaml` would otherwise emit `deps: []` on every save and clutter ticket files.

### `dep-tree` data shape

```clojure
{:id "kno-A"
 :ticket {:frontmatter {...} :body "..."}
 :children [{:id "kno-B" :ticket ... :children [...]}
            {:id "kno-G" :missing? true}
            {:id "kno-D" :ticket ... :seen-before? true}]}
```

Default mode threads a `seen` set through siblings left-to-right: the first occurrence of an id expands; later occurrences become leaf `:seen-before?` markers. `:full?` ignores `seen` and only breaks on ancestors (the current path), so true cycles in the input graph still terminate the walk — `dep tree` is safe on a graph that contains pre-existing cycles even with `--full`.

A missing root id returns a single `{:id <id> :missing? true}` node — the renderer emits one `[missing]` line. This avoids a special-case nil return at the boundary.

### `dep-tree` text rendering

`output/render-tree-lines` carries three pieces of state down the recursion: the cumulative `prefix` (whitespace + `│` continuations from ancestors), the immediate `connector` (`├── `, `└── `, or `""` at the root), and the `child-prefix` that descendants will inherit (`│   ` for non-last branches, `    ` for last). This formulation matches the standard ASCII-tree algorithm and produces the expected:

```
kno-A  Alpha
├── kno-B  Beta
│   └── kno-D  Delta
└── kno-C  Gamma
    └── kno-D  Delta ↑
```

`[missing]` and `↑` are appended as suffixes to the node label. The renderer reuses `extract-title` (the same H1-stripping helper used by `ls`).

### `dep-tree --json` shape

Bare nested object, no envelope:

```json
{"id": "kno-A", "title": "Alpha", "status": "open",
 "deps": [{"id": "kno-G", "missing": true},
          {"id": "kno-D", "title": "Delta", "status": "open", "seen_before": true}]}
```

Missing nodes carry `missing: true` and stop. Seen-before nodes carry `seen_before: true` (snake_case for jq parity with `external_refs`) and stop. Normal leaves carry `deps: []` so consumers can iterate without nil-checking. Snake_case `seen_before` was chosen over `seen-before` to match the existing JSON convention from slice 2.

### Broken-ref warnings on `show`

`cli/show-cmd` now does an extra `store/load-all` to compute `query/broken-refs` for the loaded ticket and prints one stderr line per broken `:deps` / `:parent` entry, framed as `knot: <src-id>: <kind> reference <id> is missing`. The rendered ticket still goes to stdout and exit is 0 — broken refs never abort. The N+1 cost of the extra load is acceptable at personal-project scale; if it becomes painful, slice 6's inverse-section computation will already need the all-tickets seq and can share the read.

### `ready` / `blocked` sort comparator

Priority asc then `:created` desc, with both fields nil-safe: tickets without `:priority` sort after any explicit priority (`Long/MAX_VALUE` fallback), tickets without `:created` sort last within their priority bucket (empty-string fallback). The descending order on `:created` is implemented by inverting the second `compare` call rather than reversing the list — keeps the single-pass sort stable.

### CLI dispatch: nested `dep tree` / `dep cycle`

`main/-main` routes `"dep"` to a `dep-handler` that peeks at the second token to choose between `dep tree`, `dep cycle`, and the edge form `dep <from> <to>`. The shared `edge-handler` is reused for both `dep` and `undep` since their argument shape and error semantics are identical (modulo cycle rejection, which only fires for `dep`).

### Tests not added (deferred to later slices)

- Computed inverse sections (`## Blockers`, `## Blocking`, `## Children`, `## Linked`) on `show`. Per AC, deferred to slice 6 (issue-0006). The `query/broken-refs` helper added here will feed those sections without further work.
- Symmetric `:links` (the inverse-of-self relationship). Slice 6.
- `dep tree` color output. The text renderer is plain ASCII for now; coloring status/title can layer on once `output/colorize` is reused here.
- `--mode` / status filters on `ready`/`blocked`. Deferred to slice 9 (issue-0009).
