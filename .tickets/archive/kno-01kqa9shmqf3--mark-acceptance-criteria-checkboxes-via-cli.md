---
id: kno-01kqa9shmqf3
title: Mark acceptance-criteria checkboxes via CLI
status: closed
type: feature
priority: 2
mode: hitl
created: '2026-04-28T15:02:17.495095855Z'
updated: '2026-05-01T03:03:08.271456083Z'
closed: '2026-05-01T03:03:08.271456083Z'
tags:
- v0.1
- cli
- body
deps:
- kno-01kqa804gmgx
---

## Description

Acceptance criteria today live as freeform `- [ ]` markdown checkboxes in the body. There is no API to flip them — `edit` opens an editor, `add-note` only appends to `## Notes`, and `status`/`close` touch frontmatter only. This crosses the deliberate freeform-body boundary we drew in v0 (story 31) into structured territory, so it ships post-v0.

## Design

Open questions to resolve before implementation:

1. **Indexing scheme** — three candidates:
   - Positional 1..N within the section. Simple; breaks if the user reorders or inserts items.
   - Substring match against the criterion text. Robust to reordering; ambiguous when two items share a prefix.
   - Embedded markers (`- [ ] <!--ac-1--> Foo`). Most robust; visually noisy in plain markdown.

2. **Section scope** — only `## Acceptance Criteria`, or any `- [ ]` anywhere in the body? The latter generalizes to checking off task lists under `## Notes` or freeform sections.

3. **CLI shape** — `knot ac check <id> <n>` (subcommand namespace), `knot check <id> <n>` (top-level verb), or `knot ac done <id> <substring>` (verb-named action)?

4. **Reverse + inspect ops** — do we need `uncheck` / `undone` and a `knot ac list <id>` that prints the indexable items so callers know what `<n>` refers to?

5. **Behavior on missing section / missing index / already-checked item** — no-op vs `ex-info`? Mirror the `undep` idempotent-no-op precedent or the `link` missing-id error precedent?

6. **Round-trip discipline** — must preserve the rest of the body verbatim. No reflow, no normalization of whitespace, no reordering of items. The freeform-body regression tests added in issue 0009 set the bar.

## Acceptance Criteria

- [ ] Design questions above are resolved and recorded under `## Design` (or in a follow-up note)
- [ ] One or more CLI commands implemented for flipping AC items done and undone
- [ ] Body round-trip preserves every byte outside the flipped checkbox (locked in by tests, mirroring `freeform-body-round-trip-test`)
- [ ] Tests cover: chosen indexing scheme, idempotent re-flip, missing section, missing index, reverse op, multi-flip stability
- [ ] `knot show` continues to render the body verbatim, including `[x]` markers, with no special formatting
- [ ] `:updated` frontmatter timestamp bumps on every flip (re-uses the `store/save!` path so archive routing still works if the ticket is also closed)
- [ ] Usage text and any docs are updated

## Notes

**2026-05-01T03:03:08.271456083Z**

Superseded by kno-01kqgqdxbxye (promote ACs to frontmatter). Q8 of the v0.3 API review chose to promote ACs to structured data rather than add CLI to flip in-body checkboxes; the new ticket subsumes this work.
