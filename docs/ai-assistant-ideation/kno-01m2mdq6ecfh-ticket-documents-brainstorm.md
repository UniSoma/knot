# Ticket documents — brainstorm (kno-01m2mdq6ecfh)

Design for attaching any number of documents to a knot ticket, each carrying a
title, a type and a body, with types configured in `.knot.edn` and PUT update
semantics.

## Requirements, fixed by the developer

Not open for challenge; everything below serves these.

- A ticket may hold any number of documents.
- Each document carries a title, a type and a body.
- Document types are defined in `.knot.edn`.
- Update is a PUT — whole-document replace, never a PATCH.
- A document is its own markdown file with YAML frontmatter carrying title,
  type and the owning ticket id.

## Chosen approach

A document is a markdown file at `<tickets-dir>/docs/<doc-id>--<slug>.md`, the
same shape a ticket has. Frontmatter carries `id`, `ticket`, `title`, `type`,
`created` and `updated`.

The ticket-to-documents relation is computed by filtering the document corpus on
`ticket`. Nothing is stored on the ticket, so there is no list to drift — this
mirrors `query/children`, which computes the parent edge by filtering tickets on
`:parent` rather than storing a child list.

The command is `knot document`, registered as a parent help-registry entry with
a `:subcommands` vector plus one entry per subcommand, exactly as `knot dep
tree` is registered.

### Why a subdirectory works

`store/load-all` globs `"*.md"` **non-recursively** over the tickets directory
and again over `archive/`. Verified empirically: that glob sees `a.md` and not
`docs/b.md`. So documents in a subdirectory can never be parsed as tickets.

The same property is a hazard elsewhere, and the design has to pay for it — see
the `check` decision below.

## Decisions

**Documents do not follow their ticket into `archive/`.** Identity rides the
`ticket` frontmatter field, which survives close and reopen; a path merely
locates a file on one machine (ADR 0016). Relocation would buy nothing and cost
a reopen-side sweep. An earlier draft justified this by atomicity, which was
wrong: `save!`'s guarantee is per file, and N independent single-rename moves
each keep their own file in exactly one place, so there was never a
ticket-plus-documents transaction to break.

**`check/scan` grows a third glob arm.** It currently globs the same two
non-recursive paths as `load-all`, so the mechanism that hides documents from
the loader hides them from the validator too — and the acceptance criterion
requiring `check` to report a bad type would have had no implementation. The arm
collects into a separate `:documents` slot rather than `:tickets`, so a document
is never validated as a malformed ticket. Its `:scanned` map gains a `docs` arm,
which is a pinned-contract change.

**Type validation does not reuse `check-enum`.** That builder reads a scalar
field on a ticket, and its `(seq allowed)` guard means an empty allow-list skips
validation entirely. Documents get their own validator.

**`:doc-types` and `:default-doc-type` both get real defaults.** Without a
default in `config/default-config`, a project that never configured
`:doc-types` would accept any type through the empty-list path above, making the
allow-list requirement unverifiable. Both keys join `config/known-keys`, taking
it from fourteen entries to sixteen, and `validate!` asserts the default is a
member of the list.

**The write gate and the check gate disagree by design.** `:doc-types` is
mutable configuration validating immutable stored data. Removing a type refuses
new writes of it, has `check` report the existing files that use it, and never
silently rewrites or drops anything.

**PUT refuses a missing target.** Upsert-on-PUT means a mistyped id silently
creates a second document instead of updating the intended one — and because PUT
replaces the body wholesale, the evidence of the mistake is a document that
looks correct. Creation is `knot document add`.

**A document is addressed by its own id.** The owning ticket plus a title is
accepted as a convenience selector but refuses on ambiguity, listing candidate
ids, because titles are mutable and not unique and PUT destroys what it
overwrites. The document resolver names its own three matching layers explicitly
rather than inheriting `resolve-id`'s, whose third layer strips the project
prefix to match a bare ULID and would not strip a document id correctly.

**`knot delete` refuses without `--cascade`** when the target owns documents,
enumerating them — mirroring the existing `has_incoming_refs` envelope. With
`--cascade`, documents are deleted **last**: the existing cascade already writes
referrers first and the target last so a mid-write failure never leaves dangling
refs against a gone target, and the same reasoning applies here. Deleting
documents first and then failing on the ticket would destroy them under a live
ticket — unrecoverable and undetectable. Deleting them last means a failure
leaves orphans, which the new orphan check code reports by design.

**`show` renders a `## Documents` section in both output modes**, and the
heading joins `ticket/reserved-section-owners` as a sixth row,
`{:field "ticket" :inverse? true}`. The table already carries two `:inverse?`
rows that read backwards from *other tickets'* fields — `Blocking` from their
`:deps`, `Children` from their `:parent` — so a backward read is the table's
existing idiom, not a distortion of it; documents only move where the read
happens, from another ticket to another corpus. Rendering in one mode and not
the other would have the two outputs disagree about whether documents exist.
Zero `## Documents` headings exist in the repo today, so the migration cost of
reserving the heading is zero now and non-zero later.

**`show --json` carries `documents` as metadata only** — id, title and type,
never bodies — always an array, present when empty. The empty-array guarantee
earns its keep on the ticket's own envelope, which is what a consumer reads;
bodies would make the most common read unbounded in size.

**The atomic-write primitives are extracted, not copied.** `save!` carries
roughly seventy lines of atomic write, cross-directory rename and straggler
sweep, and `save-new!` another fifty-five including the `CREATE_NEW` retry loop.
Documents need the write core and none of the status-driven routing or `:closed`
stamping. A parallel copy would mean every future fix to the atomicity story is
made twice and the second copy is the one that gets missed.

**Document ids carry a distinct segment** so a human reading `kno-…` can tell
which corpus it addresses and a mistyped command fails loudly instead of
resolving in the wrong one.

**A stray document file in the tickets directory gets its own diagnosis.**
Frontmatter carrying a `ticket` field and no `status` is a recognizable
signature, and deserves better than the generic parse error `check` would
otherwise emit.

## Risks accepted

- **No lost-update protection.** PUT makes a concurrent-write loss total rather
  than partial. Consistent with knot's documented no-locking model; the design
  placeholder is `kno-01kqgqaxzx98`.
- **`show` does a second directory walk.** It already reparses the full corpus
  for its inverse sections, so this is consistent with the stated no-index
  design.
- **Two pinned contracts change**: the `show --json` envelope and `check`'s
  `:scanned` map. Both need `json_contract_test` rows and a `bb gen:schema` run
  in the same commit.

## Prior art mirrored

| Concern | Mirrored from |
|---|---|
| Backward-computed relation, nothing stored on the parent | `query/children`, `query/inverses` |
| Derived `## ` section refused in bodies and flagged by `check` | `ticket/reserved-section-owners` |
| Refuse-and-enumerate before a destructive delete | `cli/incoming-refs` + the `has_incoming_refs` envelope |
| Orphaned reference reporting | `check/check-unknown-id` |
| Two-token command registration | `:dep/tree`, `:skill/install` |
| Allow-list config key with a default | `:types`, `:modes` |
| Atomic create with collision retry | `store/save-new!` |

## Naming

The command is `knot document`, not `knot doc`. This repo already uses `doc` to
mean *documentation* — `doc_flags_test.clj` and `doc_codes_test.clj` are guards
this story must keep green. `doc_flags_test` also scans agent-facing markdown
with `knot\s+([a-z][a-z-]*)(?:\s+([a-z][a-z-]*))?`, so prose mentioning a `knot
doc` command would resolve to a real entry and have its tail scanned for flags.

## Out of scope

- Binary or non-text attachments.
- Optimistic concurrency control (`kno-01kqgqaxzx98`).
- Documents on anything other than a ticket.
- Any listing-view or `prime` surface: a count column would force every listing
  to load the whole document corpus to render one integer nobody filters on, and
  `prime` is push-surface budget under ADR 0017.
