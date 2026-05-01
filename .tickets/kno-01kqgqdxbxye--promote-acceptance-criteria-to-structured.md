---
id: kno-01kqgqdxbxye
title: Promote acceptance criteria to structured frontmatter with rendered display
status: open
type: feature
priority: 3
mode: afk
created: '2026-05-01T02:56:02.941680078Z'
updated: '2026-05-01T02:57:45.617519664Z'
parent: kno-01kqa9shmqf3
tags:
- v0.3
- cli
- migration
- body
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqcqmy19
- kno-01kqgqc2ks70
---

## Description

Move acceptance criteria from freeform markdown checkboxes in the body to structured frontmatter, with `knot show` rendering them as a markdown checklist at display time (same pattern as `## Linked` / `## Blockers` / `## Children`).

Resolves the parent ticket kno-01kqa9shmqf3 (mark AC checkboxes via CLI) by removing the indexing-scheme problem entirely — once ACs are structured, the whole positional/substring/embedded-marker debate evaporates.

**Schema change:**

Frontmatter gains:

```yaml
acceptance:
  - title: "First criterion"
    done: false
  - title: "Second criterion"
    done: true
```

The `## Acceptance Criteria` section is **never stored** in the ticket body. `knot show` synthesizes it from frontmatter on display, exactly like `## Linked` is synthesized from the `links` field today. Single source of truth.

**Migration:**

One-shot script over existing tickets at v0.3 cut: parse `## Acceptance Criteria` section from each ticket body, lift each `- [x] foo` / `- [ ] foo` line into a frontmatter `acceptance` entry (`{title, done}`), then strip the body section. Idempotent (no AC section → no-op).

**CLI surface:**

- `knot create --acceptance "..."` (already exists) appends to the frontmatter list. Single-flag-per-criterion model: pass the flag multiple times to add multiple ACs, OR accept a newline-delimited blob — pick during implementation.
- `knot update --ac "<title>" --done` (or `--undone`) flips a single entry. Composes with the `update` command from kno-01kqgqcqmy19.
- `knot list --acceptance-complete=false` filter (one-line frontmatter scan).

**Check integration:**

Add `acceptance_invalid` error code to `knot check` (kno-01kqgqc2ks70): catches malformed frontmatter `acceptance` entries (missing `title` or `done`, wrong types, etc.).

**Cost we accept:**

Humans authoring an AC by typing `- [ ]` in `\$EDITOR` no longer works. They edit the frontmatter `acceptance` list instead — same way they edit `tags`. The freeform-body principle holds for prose sections (`## Description`, `## Design`, `## Notes`); ACs are the deliberate structured exception.

## Acceptance Criteria

- [ ] Frontmatter schema gains `acceptance: [{title, done}]` list
- [ ] `knot show` renders `## Acceptance Criteria` from frontmatter as `- [x]` / `- [ ]` markdown (no body section stored)
- [ ] One-shot migration script lifts existing body ACs to frontmatter and strips the section; idempotent on already-migrated tickets
- [ ] Migration runs cleanly against this project's own tickets at v0.3 cut (verify in CI)
- [ ] `knot create --acceptance` writes to frontmatter (decide: repeated flag vs newline-delimited blob; document the choice)
- [ ] `knot update --ac "<title>" --done` / `--undone` flips a single entry
- [ ] `knot list --acceptance-complete=false` filter implemented
- [ ] `acceptance_invalid` check code added to `knot check`
- [ ] Tests: schema round-trip, show-rendering, migration idempotence, filter, update --ac, check
- [ ] CHANGELOG covers schema change and migration
