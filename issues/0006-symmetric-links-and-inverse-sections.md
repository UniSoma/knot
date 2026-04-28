---
id: issue-0006
title: Symmetric links and computed inverse sections in show
status: done
type: afk
blocked_by:
  - issue-0005
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0006-symmetric-links-and-inverse-sections.md
---

# Symmetric links and computed inverse sections in show

## Parent document

`docs/prd/knot-v0.md`

## What to build

`knot link` and `knot unlink` with symmetric maintenance — a link from A to B is also written into B's `:links` — and the computed inverse sections in `knot show <id>`: Blockers, Blocking, Children, Linked. These sections are computed at render time from the in-memory ticket set, never persisted. Once this lands, story 3 (full graph context on `show`) is satisfied.

## User stories covered

- 3 (`knot show <id>` renders frontmatter, body, and computed inverse relationships)
- 15 (`knot link A B C` creates symmetric links across multiple tickets in one command)
- 16 (links are maintained symmetrically — write to both ticket files)

## Acceptance criteria

- [x] `knot link A B [C ...]` accepts two or more ticket IDs and creates symmetric links across all pairs in one command
- [x] `knot link` writes to all referenced ticket files, idempotent on each side (re-linking is a no-op)
- [x] `knot unlink A B` removes the symmetric link from both files; idempotent
- [x] Links survive archive transitions because `:links` references IDs not paths — verified by an integration test that links a ticket, closes it (auto-archive), then reads the link from the still-live counterpart
- [x] `knot show <id>` renders four computed sections after the body: `## Blockers` (this ticket's `:deps`), `## Blocking` (tickets that have this ticket in their `:deps`), `## Children` (tickets whose `:parent` is this ticket), `## Linked` (this ticket's `:links`)
- [x] Each computed section omits if empty
- [x] Broken references in any computed section render with `[missing]` marker (consistent with slice 5)
- [x] `--json` output for `show` includes the computed inverse fields under snake_case keys (`blockers`, `blocking`, `children`, `linked`)
- [x] Tests: symmetric write across both files, idempotent link/unlink, computed inverse sections in human and JSON output, links surviving archive transitions

## Blocked by

- issue-0005 (`issues/0005-dependency-graph.md`)

## Implementation notes

### `knot.query` API additions

Three new public, pure functions feed the inverse sections — all `(tickets ...) → data`, no I/O, Pathom3-feedable per the v0 module contract:

- `blocking <tickets> <id>` — tickets whose `:deps` contains `id`. Preserves input order. Nil-safe on tickets without `:deps`.
- `children <tickets> <id>` — tickets whose `:parent` equals `id`. Preserves input order. Nil-safe on tickets without `:parent`.
- `inverses <ticket> <tickets>` — the integration point: builds the four-section map `{:blockers, :blocking, :children, :linked}` in one call. CLI passes the result straight to the renderer.

### Inverse-entry data shape: mirror `dep-tree`

Each entry in any of the four sections is one of:

```clojure
{:id "kno-A" :ticket {full-ticket-map}}    ; resolved
{:id "kno-A" :missing? true}                ; broken ref
```

Same shape `dep-tree` uses for nodes — chosen for consistency so renderers can read entries the same way they already read tree nodes. `:blocking` and `:children` come from query results so they can never be `:missing? true`; only `:blockers` (from `:deps`) and `:linked` (from `:links`) carry missing entries because those source from the ticket's own ref lists which can name missing ids.

### `output/show-text` and `output/show-json` two-arity

Added 2-arity overloads instead of changing existing signatures: `(show-text ticket)` and `(show-text ticket inverses)`. The single-arity path is identical to before — useful for callers that don't need inverses (and keeps the issue-0005 test surface untouched). Inverse sections are appended after the body, separated by blank lines, headers `## Blockers` / `## Blocking` / `## Children` / `## Linked`. Each entry renders as `- <id>  <title>` (resolved) or `- <id>  [missing]` (broken). Empty sections are omitted entirely — no header line.

JSON inverses are top-level snake_case array fields. Entry shape mirrors `dep-tree-json`'s missing-leaf convention: `{id, title, status}` resolved or `{id, missing:true}` broken. Bare object, no envelope.

### `cli/link-cmd`: idempotent symmetric write

Two-pass implementation: first pass loads every id (raising `ex-info` with the missing id if any don't resolve — we can't write `:links` to a non-existent file); second pass uses a private `add-link` helper to conj each id into every other id's `:links`, skipping duplicates. Then a single `save!` per ticket. Returns a vector of saved paths so `main`'s handler can print one line per modified file.

The strict "all ids must resolve" stance differs from `dep-cmd`'s lenient broken-ref policy: dep is one-sided (only writes to `from`), but link writes to both sides — a missing target has no file to update, so the symmetric-write contract can't be honored.

### `cli/unlink-cmd`: lenient on `to`

`from` must resolve (we modify its file); a missing `to` is tolerated — we still remove the entry from `from`'s `:links` and skip the absent side. Empty `:links` after removal drops the key entirely via `(update :frontmatter dissoc :links)`, mirroring `undep-cmd`'s on-disk-cleanliness rule. Returns a vector of saved paths (1 when only `from` exists, 2 when both do).

### Archive survival is free

The PRD invariant — `:links` references ids, not paths, and `store/load-all` spans both live and archive — is what makes archive survival just work. No special code needed: when A is closed and moves to `.tickets/archive/`, B's `## Linked` section still resolves "Alpha" because `query/inverses` runs against the full `load-all` result. The integration test pins this behavior.

### `cli/show-cmd` reuses one `load-all` call

`show-cmd` already needed `load-all` for issue-0005's broken-ref warnings. The same call now also feeds `query/inverses` — one filesystem walk per `show`, not two. Issue-0005's note ("slice 6's inverse-section computation will already need the all-tickets seq and can share the read") landed exactly as predicted.

### Pre-existing test tightened

`integration_test.clj`'s `dep-undep-end-to-end-test` line 410 used `(not (str/includes? out (str "- " a-id)))` to assert "rejected dep wasn't persisted". That check was a substring proxy that worked only when computed sections didn't exist; with `## Blocking` now legitimately listing `a-id` on `b`'s show output, the proxy broke. Replaced with `## Blockers` and `deps:` substring checks that name the actual contract: `b`'s frontmatter has no `:deps` key.

### Tests not added (deferred)

- `## Notes` semantic anchor and `add-note`'s editor/stdin-piped/explicit-text writer paths — slice 7 (issue-0007).
- Symmetric link maintenance through `knot.store/save!` — kept out of `store` deliberately. The PRD's module split puts symmetric maintenance in `knot.store`, but lifting it there would force `save!` to take the all-tickets seq for cycle/symmetry rules. The two-callsite `link-cmd`/`unlink-cmd` shape is simpler and survives until a third caller appears.
