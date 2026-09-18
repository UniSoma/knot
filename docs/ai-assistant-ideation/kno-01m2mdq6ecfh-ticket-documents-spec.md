# Ticket Documents Design Spec

**Story:** Attach documents to tickets: title, type and body, with config-defined types and PUT updates
**Tier:** System — spans config, storage, validation, query, CLI, help and output, modifies three already-pinned JSON contracts, and requires a successor ADR.
**Brainstorm:** `docs/ai-assistant-ideation/kno-01m2mdq6ecfh-ticket-documents-brainstorm.md`
**Revision:** second, after a six-seat review round. Changes are listed under Review-driven changes.

## Problem

A knot ticket carries a title, structured frontmatter and one markdown body. Any
longer-form material belonging to a ticket — a specification, an implementation
plan, a reference document — has nowhere to live but that single body, where it competes with
the description for one flat sequence of `##` sections and cannot be typed,
listed or addressed on its own. Work that produces documents therefore keeps
them outside the tracker, where they drift from the ticket that motivated them.

## Goal

A ticket can carry any number of independently addressable documents, each with
a title, a configured type and a body, managed entirely through the `knot` CLI.

**Success signals:** every document operation is available in both human and
`--json` output; `knot check` reports each document inconsistency under its own
code; the full suite and both documentation-drift guards stay green.

## Changes Since Last Cycle

Four changes after the maintainer's first review on the upstream PR. Each
reverses or extends something below, so the original text is left in place and
marked rather than rewritten, and the reasoning stays readable.

- **Reversed: "any document surface in the listing views or `knot prime`" is no
  longer out of scope.** `ls`, `ready`, `blocked`, `closed` and `prime` now show
  a `DOCS` column naming the types a ticket owns. The rejected alternative in
  Design assumed the question "does this ticket already have a spec?" was worth
  an extra command; in practice an agent deciding what to pick up will not run
  it, so the answer has to be where it is already looking.
- **Added: `:docs-dir`.** The corpus root is configurable rather than fixed at
  `<tickets-dir>/docs`, and must resolve inside the project — a `.knot.edn`
  travels with the repo, so a clone cannot be made to read or write outside it.
- **Added: `:required-docs`.** A transition into a named status is refused until
  the ticket owns a document of every type that status requires. This is what
  makes `:doc-types` load-bearing: an allow-list nothing acts on only prevents a
  typo, and the maintainer's review said so.
- **Unchanged and reaffirmed: R14.** Documents still do not move when their
  ticket changes status. Moving them was considered and dropped; `:docs-dir`
  makes staying put a property of where they live rather than a surprise.

## Scope

**In scope:** document storage as individual files under a per-ticket directory;
the `knot document` command group; `:doc-types`, `:default-doc-type`,
`:docs-dir` and `:required-docs` configuration; four document validations in `knot check`; a `## Documents`
render in `knot show`; a `documents` array in the `show --json` envelope;
document-aware `knot delete`; regeneration of `knot.schema.json`; the pull, push
and pointer context surfaces; a successor ADR to ADR 0008 on cascade ordering.

**Out of scope:** binary or non-text documents; documents attached to anything
other than a ticket; optimistic concurrency control (`kno-01kqgqaxzx98`);
~~any document surface in the listing views or `knot prime`~~ (reversed — see
Changes Since Last Cycle); centralizing `fs/unixify`
(see Risks — that trigger fired in an earlier story and is not this story's to
discharge).

**Non-goals:** documents are not a second ticket type — no status, no lifecycle,
no dependency graph, no archive behaviour. Nothing here introduces a second
addressable corpus into ticket id resolution.

**A document is not a note.** knot already carries timestamped notes, appended
under the body's `## Notes` section by `add-note`, and they remain the surface
for short chronological remarks. A document is titled, typed, independently
addressable and separately stored. The two do not overlap and neither replaces
the other; `note` is deliberately absent from the default type list for that
reason.

## Requirements

| ID | Priority | Requirement |
|----|----------|-------------|
| R1 | MUST | A ticket may own zero, one or many documents; a document belongs to exactly one ticket, named by an owning-ticket field in the document's own frontmatter. |
| R2 | MUST | No list of documents is stored on the ticket. The ticket-to-documents relation is computed by reading documents backwards. |
| R3 | MUST | A document carries a title, a type and a body, and round-trips through write-then-read unchanged, including bodies containing `---` lines, `##` headings and trailing whitespace. |
| R4 | MUST | Document types are constrained by a `:doc-types` allow-list in `.knot.edn`, which has a default, so an unconfigured project still enforces the list. |
| R5 | MUST | A write of a document whose type is outside the allow-list is refused before anything is written. |
| R6 | MUST | The update operation replaces title, type and body together. No invocation merges into a stored document. |
| R7 | MUST | An update targeting a document that does not exist is refused with a named error, never an implicit create. |
| R8 | MUST | Documents are stored under a per-owning-ticket directory. The owning ticket id never appears in the leading segment of a document filename. |
| R9 | MUST | The document's own identifier leads its filename, so that the store's straggler sweep continues to operate on a set that is one record's files. |
| R10 | MUST | The owning-ticket frontmatter field is authoritative; the directory is a locator. Where they disagree, the field wins. |
| R11 | MUST | `knot check` reports, each under its own code: a type outside the allow-list; a document whose owning-ticket field resolves to nothing; a document whose directory disagrees with its field, in both directions; and two files claiming the same document identifier. |
| R12 | MUST | The agreement check is evaluated before the orphan check, so a misplaced document yields one issue with one repair rather than two issues with contradictory repairs. |
| R13 | MUST | Documents are not parsed as tickets by any corpus reader, and a ticket is never parsed as a document. |
| R14 | MUST | A document's location does not change when its owning ticket changes status. |
| R15 | MUST | A retitle does not rename the document's file. The filename slug is recovered from the existing file on save, as it is for tickets. |
| R16 | MUST | A slug that no longer matches its title is not an error and MUST NOT become a check code. |
| R17 | MUST | `knot delete` does not silently orphan documents: it refuses while the target owns any, and under cascade removes them after the ticket rather than before. |
| R18 | MUST | Every document read and write accepts `--json` and emits the standard envelope. |
| R19 | MUST | `knot show` reports a ticket's documents in both human and `--json` output, and the two agree about which documents exist. |
| R20 | MUST | `show --json` carries documents as an array present and empty when the ticket owns none, carrying metadata only, never bodies. |
| R21 | MUST | A document heading written by hand into a ticket body is refused by the write surface and reported by `knot check`, as the existing derived headings are. |
| R22 | SHOULD | A document is addressable by a short unambiguous prefix of its identifier, and by owning-ticket-plus-title where that pair is unique. |
| R23 | SHOULD | An ambiguous document selector is refused with the candidates named, never resolved to a first match. |
| R24 | MUST | A document file found in the ticket directory is diagnosed as a misplaced document, not as a malformed ticket. |
| R25 | MUST | An absent owning-ticket directory and an empty one are treated identically, both meaning "no documents". |
| R26 | MUST | All three ADR-0017 context surfaces — pull (`knot --help`, `knot help <topic>`), push (`knot prime`), pointer (the bundled skill) — are updated in the same commit, and the installed skill copy is regenerated when any `references/*.md` changes. |
| R27 | MUST | Every new error or check code is emitted as a literal in a location the catalogue guard's extractor actually reads, or the extractor is extended in the same commit. |
| R28 | MUST | `knot.schema.json` is regenerated from the runtime rather than hand-edited. |
| R29 | MUST | The two new config keys appear in every destination the existing keys appear in, including the `knot init` stub and the README table. |
| R30 | SHOULD | No `doc` or `docs` alias is registered for the `document` command group. |
| R31 | MUST | A document body is supplied through the layered input the repo already uses for note content: explicit argument, else stdin when stdin is not a tty, else the editor. |
| R32 | MUST | A blank body resolves to one stated outcome — a valid empty document — rather than being left to the implementation. |
| R33 | MUST | Replace preserves the document's creation timestamp and bumps its updated timestamp. |
| R34 | MUST | The catalogue guard is itself tested: a deliberately undocumented document check code fails it. |
| R35 | MUST | The default `:doc-types` is `["spec" "plan" "other"]` with `:default-doc-type` `"other"`. |
| R36 | MUST | The fault-injection test for cascade ordering lives in the already-serial `cli_test` namespace, and the pure round-trip tests live in a namespace free of global-mutation markers. |
| R37 | MUST | The extracted shared write primitive does not carry the ticket path-finder's semantics into the document path. |
| R38 | MUST | Reserving the documents heading is treated as a breaking change for installed projects. A pre-existing ticket body carrying that heading is reported as a **warning** under its own code, never an error, with a message stating the manual remedy; the warning self-clears once the heading is gone. The write surface still refuses newly written ones. The message states what to do without implying an automated conversion, since none is provided. |
| R39 | MUST | On a ticket whose body carries a legacy documents heading, the derived section remains the single authority for which documents exist. The authored text renders as ordinary body content and is never merged with, or substituted for, the derived section, and the human and JSON modes continue to agree. |

## Design

**Approach.** A document is a markdown file with YAML frontmatter, stored at
`<tickets-dir>/docs/<owning-ticket-id>/<document-id>--<slug>.md`. The ticket
loader's pattern does not descend into subdirectories, so the corpora cannot
contaminate each other. A ticket's documents are found by reading one directory.
The owning ticket is recorded in the document's own frontmatter, which is
authoritative; the directory that holds it is a locator.

**Why the owner is a directory and not a filename segment.** The store's
existing `<id>--*.md` convention means *the files of the record whose id is
this*, and the straggler sweep in `save!` is correct only under the precondition
that **the leading globbed segment uniquely identifies one record's files**.
Putting a non-unique key — an owner — in that position makes the matched set
legitimately cardinality-N, and the sweep then does exactly what it was written
to do, on data that violates its contract: saving one document deletes its
siblings. No separator or ordering trick fixes this, because the fault is in
what the matched set *means*, not in the matching. A directory carries the owner
without occupying that segment.

**Components and responsibilities.**
- A pure document module owns shape: frontmatter validation, identifier
  generation, filename derivation, and stored-to-rendered transformations. No I/O.
- The storage boundary gains document-scoped load, save, resolve and delete. Its
  atomic-write and create-exclusive primitives are extracted so both corpora
  share one implementation of the write while each keeps its own placement
  policy. The document side does not reuse the ticket's id-glob helper.
- The validation module gains a document arm collecting documents separately
  from tickets, with the four checks of R11 in the precedence of R12.
- The query module gains the backward read from documents to their owning
  ticket, alongside the existing backward reads.
- The CLI gains a command group for create, read, replace, delete and list,
  registered as a parent entry with subcommands.
- The output module gains the human and JSON renderings.

**Interfaces and contracts.**
- Create takes an owning ticket, title, type and body; mints an identifier and
  refuses an out-of-list type before writing. It relies on the existing
  idempotent parent-directory creation, so nothing pre-creates or tracks the
  owner directory.
- Replace takes a document selector and a complete title, type and body, and
  refuses a selector matching nothing or more than one. It is a single atomic
  write in place; it never renames.
- Delete removes exactly one document. List returns a ticket's documents, and
  returns empty for both an absent and an empty owner directory.
- Ticket delete refuses while the target owns documents, enumerating them; under
  cascade it removes documents after the ticket, so an interrupted cascade
  leaves reportable orphans rather than documents destroyed beneath a live
  ticket. An emptied owner directory is left in place.
- Every command emits the standard envelope under `--json`, failures carrying a
  named code.

**Data and state.** A document record holds an identifier, an owning-ticket
reference, a title, a type, creation and update timestamps, and a body. Two
configuration keys are added. **Three** already-pinned JSON shapes change: the
`show` envelope gains a documents array; `check`'s scanned counts gain a
document arm; and `info`'s allowed-values map gains the two new config keys by
the pattern the existing keys already follow.

**Prior art to mirror.**
- `src/knot/query.clj` — `children`, `inverses`: backward-computed relation.
- `src/knot/check.clj` — `check-terminal-outside-archive` (`:71-98`): a
  path-encoded record property policed in **both directions under one code**,
  with the message distinguishing. The document directory-versus-field check is
  its direct analogue.
- `src/knot/check.clj` — `check-unknown-id` for a reference resolving to nothing.
- `src/knot/cli.clj` — `incoming-refs` and `delete`'s refuse-and-enumerate shape.
- `src/knot/store.clj` — `save-new!` for create-exclusive with retry; the
  `(when (fs/directory? …))` guards in `load-all` for the absent-directory case.
- `test/knot/cli_test.clj:3251` — `with-redefs` fault injection against an
  in-process command fn, counting calls and throwing on the Nth. This is the
  pattern for AC-16.
- `src/knot/cli.clj` — `resolve-note-content` (`:844`): the layered body input,
  explicit text then stdin-when-not-a-tty then editor. This is how a document
  body reaches the command; no `--body-file` flag exists in the CLI today.
- `src/knot/help.clj` — parent-plus-subcommands registration, as `knot dep tree`.

**Key decisions.** Rationale in the brainstorm; one line each.
- Command group named in full — the short form already means *documentation*
  here, in two guards this story must keep green. No alias.
- Owner is a directory, not a filename segment — see the sweep precondition.
- Documents do not move on a status change. Cited to CONTEXT.md's *Archive
  routing*: location is one fact with the record, and documents have no status,
  so there is no routing to perform. **Not** ADR 0016, which governs the form of
  paths in the JSON envelope, not on-disk placement.
- A retitle does not rename. Renaming would make replace two operations with an
  observable interrupted state in which two files carry the same document id,
  surfacing as an unresolvable selector from a document someone merely retitled.
  It would also turn a one-line frontmatter change into a whole-document diff.
- Slug/title divergence is cosmetic and must not become a check code, or every
  retitle manufactures a finding clearable only by a rename.
- Frontmatter is authoritative and the agreement check precedes the orphan
  check, so a misplaced document does not yield two issues with contradictory
  repairs.
- An emptied owner directory is left deliberately: git does not track empty
  directories, so it never enters a commit and self-cleans at every clone;
  reporting it would make `check`'s result depend on which checkout you stand
  in, and removing it adds a filesystem operation to a delete path whose
  ordering is load-bearing.
- Replace refuses a missing target; an implicit create turns a mistyped selector
  into a second document whose replaced body looks correct.
- The default type vocabulary is `spec`, `plan`, `other`, defaulting to
  `other`, set by the developer. `note` is deliberately absent: knot already
  carries timestamped notes through `add-note`, and a `note` document type
  would put two surfaces on one meaning.
- The allow-list carries a default — the existing enum validator skips
  validation entirely on an empty set, which would make R4 and R5 unverifiable.
- Write gate and check gate disagree by design: a withdrawn type refuses new
  writes, has existing files reported, and never triggers a silent rewrite.
- Cascade removes documents after the ticket. The principle is *never leave an
  intermediate state that is silently wrong*; because R2 stores nothing on the
  ticket, a ticket that lost documents is byte-identical to one that never had
  any, so documents-first is undetectable loss while documents-last is
  reportable. This inverts ADR 0008's ordering clause and requires a successor.
- The documents heading joins the derived-section table, so the two output modes
  agree and a hand-written copy cannot drift from the real relation. Reserving
  it is nonetheless a **breaking change for the installed base**: knot ships as
  a CLI into client projects whose tickets it has never seen, so "zero such
  headings exist" is a fact about this repository and cannot be measured over
  those projects. knot has reserved a heading once before — when acceptance
  criteria moved from the body into frontmatter — and the shape it used is the
  precedent here: `check-legacy-acceptance` reports a pre-existing body section
  as a `:warning` under its own code, names the remedy in the message, and
  self-clears once the body is stripped. A newly written heading is still
  refused outright. Warning is the right severity and not merely the lenient
  one: under the acceptance-criteria precedent the body section was a *stale
  copy* of data that had moved to frontmatter, so divergence was real, whereas a
  pre-existing documents heading mirrors no corpus entry at all. The drift risk
  is therefore lower than the precedent's, and because the write surface refuses
  new ones, it cannot grow. Erroring would break `check` on upgrade for projects
  that did nothing wrong, which is the harm this decision exists to avoid.

**Alternatives rejected.**
- Documents in the ticket's own frontmatter — puts prose into YAML block
  scalars, hostile to the read-grep-diff property the tracker exists for.
- Documents as `##` sections of the ticket body — no place for a per-document
  type; collides with the flat section model.
- A document list stored on the ticket — two copies of one fact.
- Owner encoded in the leading filename segment — violates the sweep
  precondition; see Design.
- A document count column in the listing views — **not** on cost grounds, which
  do not hold: with the owner in the path a count for a whole listing is one
  directory read and a frequency map, cheaper than the listing already is.
  Rejected because it is an unfilterable per-row token cost in every agent that
  runs a listing, for a corpus where most tickets own nothing; and because a
  path-derived count is a hint while the frontmatter field is authoritative, so
  a listing would publish an unreconciled count beside a `show` that reconciles,
  which is the third view of one fact that R2 exists to prevent.

## Acceptance Criteria

### Story 1: When a ticket needs longer-form material kept with it, I want to attach a typed document, so I can find it from the ticket rather than outside the tracker.

**AC-1** — create and read back
Given a ticket exists and a type is in the allow-list
When a document is created against that ticket with a title, that type and a body
Then reading that document returns the same title, type and body.

**AC-2** — adversarial round-trip
Given a body containing a `---` line, a `##` heading and trailing whitespace
When that body is stored and read back
Then it is byte-identical to the input.

**AC-3** — many per ticket, and siblings survive a sibling write
Given a ticket already owning several documents
When one of them is written again
Then every sibling document still exists and is unchanged.

**AC-4** — type outside the allow-list is refused
Given a type absent from the allow-list
When a document creation is attempted with it
Then the command fails with a named code and no file is written.

**AC-5** — documents are not tickets
Given a ticket owning documents
When the tickets are listed
Then no document appears among them and the ticket count is unchanged.

### Story 2: When a document's content is superseded, I want to replace it wholesale, so I can be sure no stale field survives.

**AC-6** — replace is total over the document's fields
Given a stored document
When it is replaced with a new title, type and body
Then those three fields are the new values, no prior field value survives, the
creation timestamp is unchanged and the updated timestamp is bumped.

**AC-7** — replace refuses a missing target
Given a selector matching no document
When a replace is attempted
Then the command fails with a named code and nothing is created.

**AC-8** — ambiguity is refused, not resolved
Given one ticket owning two documents sharing a title
When that ticket-and-title pair is used as a selector
Then the command fails naming both candidate identifiers.

**AC-9** — a retitle does not rename the file
Given a stored document
When it is replaced with a different title
Then its file path is unchanged and no second file exists.

**AC-9a** — the stale slug is deliberately cosmetic
Given a document retitled so its filename slug no longer matches its title
When the project is checked and the document is listed
Then the check reports nothing, and the listing shows the new title.

**AC-9b** — delete removes exactly one
Given a ticket owning several documents
When one is deleted
Then it is gone and every sibling is byte-identical.

**AC-9c** — delete refuses a missing or ambiguous selector
Given a selector matching no document, and separately one matching two
When a delete is attempted with each
Then each fails with a named code and nothing is removed.

**AC-9d** — a body arrives by argument, by stdin and by editor
Given a document body supplied as an explicit argument, separately on stdin, and
separately through an injected editor function
When a document is created each way
Then all three store the same body, and an adversarial body survives the stdin
path. The editor path needs no global redefinition: the input resolver takes the
editor function as an option, so this test stays in a parallel namespace.

### Story 3: When a project's documents or configuration drift, I want inconsistency reported precisely, so I can trust the store.

**AC-10** — invalid stored type reported
Given a stored document whose type is outside the allow-list
When the project is checked
Then an issue is reported under its own code naming that document.

**AC-11** — orphaned document reported
Given a stored document whose owning-ticket field resolves to nothing, in the matching directory
When the project is checked
Then an orphan issue is reported naming that document.

**AC-12** — misplaced document reported once, not twice
Given a document whose directory names a dead id but whose field names a live ticket
When the project is checked
Then exactly one issue is reported, the disagreement, and no orphan issue.

**AC-12a** — the remaining disagreement quadrants
Given three documents: one whose directory names a live ticket while its field
resolves to nothing; one where directory and field name the *same* dead id; and
one where they name *two different* dead ids
When the project is checked
Then the first reports the disagreement only, the second reports the orphan
only, and the third reports the disagreement — because the two ids differ, a
real disagreement exists and must not be suppressed by the orphan rule. Each
case asserts the issue count, not merely the presence of an issue.

**AC-13** — agreement is checked in both directions
Given a document in the wrong owner directory, and separately one whose field names a ticket other than its directory
When the project is checked
Then both are reported under the same code with distinguishing messages.

**AC-14** — duplicate document identifier reported
Given two files claiming the same document identifier
When the project is checked
Then an issue is reported naming both paths.

**AC-14a** — a configured allow-list draws no unknown-key warning
Given a project whose `.knot.edn` sets both document-type keys
When any command runs
Then no unknown-key warning is emitted for either.

**AC-14b** — a default outside its own list fails at command start
Given a project whose default document type is absent from its allow-list
When any command runs
Then it fails at start with a named configuration error.

**AC-14c** — the catalogue guard is tested, not merely run
Given a document check code emitted but deliberately absent from the catalogue
When the catalogue guard runs
Then it fails, naming that code.

**AC-15** — allow-list enforced without configuration
Given a project with no document-type configuration
When a document is created with an arbitrary type
Then it is refused, because the allow-list has a default.

**AC-16** — misplaced-in-ticket-directory diagnosed
Given a document file placed in the ticket directory
When the project is checked
Then it is reported as a misplaced document, not as a malformed ticket.

**AC-16a** — a pre-existing documents heading warns rather than errors
Given a ticket body that already carried the documents heading before upgrade
When the project is checked
Then it is reported as a warning under its own code naming the remedy, the check
does not fail on it, and the warning disappears once the heading is removed.

**AC-16b** — show on a ticket carrying a legacy heading
Given a ticket whose body already carried the documents heading before upgrade,
and which owns documents
When it is shown with and without the JSON flag
Then the derived section lists exactly the documents the backward read finds, the
authored text appears only as ordinary body content, and both modes name the same
documents — the authored text contributes nothing to either.

**AC-17** — hand-written heading refused and reported
Given a ticket body containing the documents heading
When it is written, and when the project is checked
Then the write is refused and the check reports it.

### Story 4: When a ticket moves through its lifecycle or is removed, I want its documents handled predictably, so nothing is lost silently.

**AC-18** — documents survive close and reopen, in place
Given a ticket owning documents
When it is closed and then reopened
Then listing returns the same set at every point, and each document's on-disk
path is identical before the close, after it, and after the reopen.

**AC-19** — delete refuses while documents exist
Given a ticket owning documents
When deletion is attempted without cascade
Then it is refused and the owned documents are enumerated.

**AC-20** — cascade removes documents after the ticket
Given a ticket owning documents, and a fault injected so the document removal fails
When it is deleted with cascade
Then the ticket is already gone and the surviving documents are reportable as orphans.

**AC-21** — absent and empty owner directories are equivalent
Given one ticket whose owner directory does not exist and one whose owner directory is empty
When each is listed
Then both return no documents and neither errors.

### Story 5: When a consumer scripts against knot, I want the document surface in the JSON contract, so I can branch on it without parsing tables.

**AC-22** — envelope on every document subcommand
Given each of the document subcommands — create, show, replace, delete, list
When each is run with the JSON flag, once succeeding and once failing
Then each emits the standard envelope, failures carrying a named code.

**AC-23** — documents array always present
Given a ticket owning no documents
When it is shown with the JSON flag
Then the documents key is present and is an empty array.

**AC-24** — metadata only
Given a ticket owning a document with a long body
When it is shown with the JSON flag
Then the entry carries identity, title and type, and no body.

**AC-25** — both output modes agree
Given a ticket owning documents
When it is shown with and without the JSON flag
Then both name the same documents.

### Traceability

| Story AC | Spec ACs | Requirements | Notes |
|----------|----------|--------------|-------|
| `:doc-types` recognised, no unknown-key warning, invalid value fails at start | AC-14a, AC-14b, AC-15 | R4, R29 | All three thirds now covered: AC-14a the no-warning half, AC-14b the fail-at-start half, AC-15 the enforcement half. |
| Zero, one or many documents, round-tripping unchanged | AC-1, AC-2, AC-3 | R1, R3 | AC-3 strengthened to prove the sweep hazard is closed. |
| Type outside `:doc-types` refused at write; `check` reports one on disk | AC-4, AC-10 | R5, R11 | |
| Update is PUT; no invocation merges | AC-6, AC-7 | R6, R7 | |
| Every read and write accepts `--json`; array present when empty | AC-22, AC-23, AC-24 | R18, R20 | |
| Three context surfaces move in the same commit; both doc guards pass | AC-14c | R26, R27, R34 | The surface half is verified by the guards themselves; the guard half is AC-14c, which tests that the catalogue guard actually sees a document code rather than merely running. |
| `knot.schema.json` regenerated, not hand-edited | — | R28 | |
| `document ls <id>` same set before close, after close, after reopen | AC-18 | R14 | |
| Round-trip fixture is adversarial | AC-2 | R3 | |

## Boundaries

- ✅ **Always** mirror the cited prior art rather than inventing a parallel
  shape; run the full suite and both documentation-drift guards; regenerate the
  schema with its generator; assert path shape by components, never by embedded
  separators.
- ⚠️ **Ask first** before changing any pinned JSON shape beyond the three named
  here; before adding a configuration key beyond the two named here; before
  changing the derived-section table by more than one row; before altering the
  catalogue guard's extractor beyond adding a source it must read.
- 🚫 **Never** copy the atomic-write or create-exclusive logic into a second
  implementation; never reuse the ticket id-glob helper for documents; never put
  the owning ticket id in a leading filename segment; never weaken or skip a
  guard to make a step pass; never hand-edit the generated schema or files under
  the tickets directory.

## Testing Strategy

| AC | Level | Notes |
|----|-------|-------|
| AC-1, AC-3, AC-6, AC-9 | integration | Through the CLI in a temporary project. AC-3 asserts sibling survival. |
| AC-2 | unit | Pure round-trip; the adversarial body is the fixture. |
| AC-5 | integration | A ticket owning documents is listed; no document appears among the tickets and the count is unchanged. Rowed explicitly — it is the one numbered AC among lettered insertions and falls inside no range above. |
| AC-16a | integration | Plant a pre-existing body heading; assert warning severity, the remedy in the message, a non-failing check, and that it self-clears. |
| AC-16b | integration | The same planted body, on a ticket that owns documents: assert the derived section is authoritative in both output modes and the authored text contributes nothing. |
| AC-4, AC-7, AC-8, AC-15 | integration | Assert exit status, named code, and that no file was written. |
| AC-10, AC-11, AC-12, AC-13, AC-14, AC-16 | integration | Planted files in a temporary project; AC-12 asserts issue *count*, not just presence. |
| AC-17 | integration | Both halves: refused write and check report. |
| AC-18, AC-19, AC-21 | integration | Close, reopen and list; absent versus empty directory. |
| AC-9a, AC-9b, AC-9c | integration | Retitle leaves the path alone and `check` stays silent; delete removes one and leaves siblings byte-identical; missing and ambiguous selectors each refused by name. |
| AC-9d | unit | All three input layers, the editor supplied as an injected option. Keep this namespace free of global-mutation markers so it stays parallel. |
| AC-12a | integration | The two quadrants AC-11 and AC-12 do not cover. Assert issue count, not just presence. |
| AC-14a, AC-14b | integration | Configured keys draw no unknown-key warning; a default outside its own list fails at command start. |
| AC-14c | integration | The guard self-test: emit a document check code, omit its catalogue row, assert the catalogue guard fails naming it. This is the AC most likely to be skipped, so it gets its own row deliberately. |
| AC-20 | **in-process, in `cli_test`** | Mirrors `test/knot/cli_test.clj:3251` — `with-redefs` on the save fn with a call counter throwing on the Nth call, driving the cascade in-process. **Not** `integration_test`: that namespace spawns a fresh `bb` per command, so a fault cannot be injected across the process boundary, and adding a global-mutation marker there would drag the suite's largest namespace into the serial phase. `cli_test` is already serial, so this costs nothing. |
| AC-22, AC-23, AC-24, AC-25 | integration | Pinned in the JSON contract suite. |

**Not automatable:** nothing. An earlier revision claimed AC-20's ordering was
not directly assertable; that was false — the harness already contains the
fault-injection idiom for this exact command path.

## Risks and Open Questions

- [P1, adopted] Owner in a leading filename segment would violate the store's
  sweep precondition. Closed by R8/R9; AC-3 is the regression test.
- [P1, adopted] Two check codes could fire on one file with contradictory
  repairs. Closed by R12; AC-12 pins the count.
- [P1, adopted] A rename on retitle would break replace's atomicity. Closed by
  R15; AC-9 is the test.
- [P1, adopted] The catalogue guard reads only two named files by literal
  pattern, so a validator in a new namespace or a code emitted through a builder
  is invisible and the catalogue goes stale while the guard stays green. Closed
  by R27.
- [P1, adopted] `info`'s allowed-values map is a third pinned shape the earlier
  revision did not name. Closed by naming it.
- [Pre-existing, not this story's to fix] ADR 0016 records four path-bearing
  envelope fields and nine `fs/unixify` sites, and says a further field is the
  signal to centralize. There are now six fields and twelve call sites, both
  passed in an earlier story. This story adds more. Flagged, not discharged.
- [Process] This story's cascade ordering inverts ADR 0008's ordering clause and
  its abort-before-unlinking guarantee, though **not** its success contract,
  which is scoped to a successful delete and survives intact. A successor ADR is
  required, in the shape ADR 0016 used to succeed ADR 0015 — a new document
  owning the ordering question, an inline marker on the affected clause, and the
  reciprocal line — stating the rule at principle level: *remove last whatever
  leaves a reportable intermediate state; a dependent side that is rewritten
  goes first, a dependent side that is destroyed goes last.*
- [Accepted, not fixed] No lost-update protection; PUT makes a concurrent write
  lose the whole document. Tracked at `kno-01kqgqaxzx98`.
- [Deferred] A frontmatter-only read path would bound per-file cost
  independently of body size, which matters for a corpus of long-form material.
  Changes no stored format, so it can land whenever. Noted so the plan does not
  foreclose it.
- [Deferred] The documents array in the `show` envelope is unbounded. A
  truncation convention belongs in the command's help notes.

## Review-driven changes

Second revision, after six seated reviewers. No P0 was raised. Changes: storage
moved from a flat directory with a corpus filter to a per-owner directory
(sweep precondition); retitle no longer renames (replace atomicity); check
precedence added (contradictory repairs); the listing-column rejection rewritten
on value and drift after its cost rationale was shown false; R26 corrected to
ADR 0017's surface vocabulary, which the earlier revision misnamed; a third
pinned JSON shape named; the no-movement decision re-cited to CONTEXT.md rather
than ADR 0016, which does not govern on-disk placement; AC-20's
not-automatable claim withdrawn as false.

## Assumptions

1. Documents are text. Nothing validates or transports binary content, and the
   round-trip guarantee is stated for text bodies only — Impact: HIGH
   Correct this if: a document type is expected to carry a binary payload.
2. A document belongs to exactly one ticket, and ownership does not transfer —
   Impact: MEDIUM
   Correct this if: documents need reassigning between tickets.
3. Document ordering within a ticket is not significant — Impact: LOW
   Correct this if: authored ordering must be preserved and rendered.
4. A blank body produces a valid empty document rather than an error or a
   no-op — Impact: MEDIUM
   Correct this if: an empty document should be refused. Note the neighbouring
   `add-note` surface treats blank content as a no-op, so the two differ
   deliberately and that difference needs to survive review.

---
After implementing, compare results against each acceptance criterion above
and list any unmet requirements.
