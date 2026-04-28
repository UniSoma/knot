---
id: issue-0008
title: Layered partial ID resolution
status: ready
type: afk
blocked_by:
  - issue-0003
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0008-partial-id-resolution.md
---

# Layered partial ID resolution

## Parent document

`docs/prd/knot-v0.md`

## What to build

Replace the exact-match-only ID resolution from earlier slices with the layered prefix-matching strategy described in the PRD: exact full match wins; otherwise prefix match against the full ID; otherwise prefix match against the post-prefix ULID portion (so `01jq8p4` works without retyping `mp-`); ambiguous matches fail with a candidate list printed to stderr. Resolution scans both `<tickets-dir>/*.md` and `<tickets-dir>/archive/*.md` so partial-ID matches work regardless of slug or archive state.

## User stories covered

- 19 (partial ID matching like `git`'s short hashes — `knot show 01jq8p4ab` resolves to the unique full ID)
- 20 (partial IDs work without the project prefix — `knot show abc...`)

## Acceptance criteria

- [ ] Single resolver function in `knot.store` (or `knot.ticket`) used by every command that takes an `<id>` argument
- [ ] Resolution layers (in order): (1) exact full ID match; (2) prefix match against full ID (`mp-01jq8p`); (3) prefix match against post-prefix ULID portion (`01jq8p`)
- [ ] Resolver scans both live and archive directories
- [ ] Frontmatter `:id` is canonical — resolution does not depend on filename
- [ ] On a unique match, the resolver returns the full ticket; on zero matches, errors with "ticket not found: <input>"; on multiple matches, errors with the candidate IDs listed
- [ ] All previously-built commands (`show`, `status`, `start`, `close`, `reopen`, `dep`, `undep`, `dep tree`, `link`, `unlink`, `add-note`, `edit`) now accept partial IDs through the unified resolver
- [ ] Tests: each resolution layer's positive case, ambiguity handling with candidate list output, resolution across live + archive

## Blocked by

- issue-0003 (`issues/0003-lifecycle-transitions-and-archive.md`)
