---
id: kno-01kqgqdxbxye
title: Promote acceptance criteria to structured frontmatter with rendered display
status: closed
type: feature
priority: 3
mode: afk
created: '2026-05-01T02:56:02.941680078Z'
updated: '2026-05-05T10:23:23.153113436Z'
closed: '2026-05-05T10:23:23.153113436Z'
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
acceptance:
- title: 'Frontmatter schema gains `acceptance: [{title, done}]` list'
  done: false
- title: '`knot show` renders `## Acceptance Criteria` from frontmatter as `- [x]` / `- [ ]` markdown (no body section stored)'
  done: false
- title: One-shot migration script lifts existing body ACs to frontmatter and strips the section; idempotent on already-migrated tickets
  done: false
- title: Migration runs cleanly against this project's own tickets at v0.3 cut (verify in CI)
  done: false
- title: '`knot create --acceptance` writes to frontmatter (decide: repeated flag vs newline-delimited blob; document the choice)'
  done: false
- title: '`knot update --ac "<title>" --done` / `--undone` flips a single entry'
  done: false
- title: '`knot list --acceptance-complete=false` filter implemented'
  done: false
- title: '`acceptance_invalid` check code added to `knot check`'
  done: false
- title: 'Tests: schema round-trip, show-rendering, migration idempotence, filter, update --ac, check'
  done: false
- title: CHANGELOG covers schema change and migration
  done: false
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

## Notes

**2026-05-05T10:23:23.153113436Z**

TDD vertical slices: 13 RED→GREEN cycles delivered the schema change end-to-end.

SCHEMA: `acceptance: [{title, done}]` lives in frontmatter; the `## Acceptance Criteria` body section is never stored on disk. `knot show` synthesizes the markdown checklist between the body and the inverse sections via a new `knot.acceptance/render-section`.

CREATE: `--acceptance "<title>"` is now a repeatable string flag (model: `--external-ref`). `knot.acceptance/from-titles` lifts each occurrence to `{:title :done false}`. The flag drops out of the body-flag pre-extraction list (no longer multi-line markdown).

UPDATE: new `--ac "<title>" --done|--undone` triple flips one entry by exact title match. `knot.acceptance/flip` is the pure transform; cli-layer `validate-ac-flip-opts!` and `apply-ac-flip` enforce the mutual-exclusion contract (--done/--undone exclusive, --ac required, --done/--undone require --ac, missing title throws). `--acceptance` removed from `knot update` entirely.

LIST FILTER: new `:acceptance-complete` criterion in `query/filter-tickets`, surfaced as `--acceptance-complete=false|true` on list/ready/blocked/closed. nil-AC tickets excluded from both filters — the dimension is structured AC work, and absent ACs mean it does not apply. main.clj `->set` extended to wrap booleans.

CHECK: new `:acceptance_invalid` per-ticket validator catches non-list shape, non-map entries, missing/non-string title, missing/non-boolean done. One issue per offending field.

MIGRATION: hidden `knot migrate-ac` subcommand walks live + archive via `check/scan`, runs `acceptance/migrate-ticket`, saves only changed files. Parser accepts both `- [ ] / - [x] / - [X]` checkbox forms and plain `- title` bullets (defaulting to done:false) — needed because real AC sections in this repo used both. Idempotent: second run is a no-op.

LIVE SMOKE on this repo: `migrate-ac` lifted 29 tickets cleanly, second run reported `Migrated 0 tickets (60 unchanged, 60 total)`. `knot check` passes. `update --ac --done` flipped a real entry round-trip. `list --acceptance-complete=false` returns the correct set including this ticket.

DEEP MODULE: new `knot.acceptance` ns owns the pure transforms (render-section, from-titles, flip, parse-body-section, strip-body-section, migrate-ticket); output/cli/check/query delegate to the small public surface.

Tests: 288 / 2651 / 0 failures (was 277 / 2550; +11 deftests, +101 assertions). New test file: test/knot/acceptance_test.clj. Lint baseline unchanged (4 errors / 5 warnings, all pre-existing).

Files: src/knot/acceptance.clj (new), src/knot/{cli,check,help,main,output,query}.clj, test/knot/acceptance_test.clj (new), test/knot/{cli,check,help,integration,output,query}_test.clj, CHANGELOG.md, .claude/skills/knot/SKILL.md.

Committed: 4fe5384.
