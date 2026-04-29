---
id: kno-01kqdg8b8ww7
title: Move ticket title from body H1 to frontmatter field
status: in_progress
type: task
priority: 2
mode: afk
created: '2026-04-29T20:52:57.242922337Z'
updated: '2026-04-29T21:14:52.127694116Z'
---

## Description

Today the title is stored as the first `# H1` line in the body, and `extract-title` parses it line-by-line on every read (called from `inverse-line`, `show-json`, the `ls` table, dep-tree text/JSON, and the prime line). Move title into the frontmatter as a first-class field so it's read directly, with no parsing.

## Design

Decisions:

1. Storage: title lives in frontmatter only. No H1 in the body, no dual storage. `extract-title` is deleted.

2. Migration: manual. Existing tickets are hand-edited to lift `# Title` into `title:` frontmatter and strip the H1 from the body. Schema change and `extract-title` deletion land in the same PR. No migration code added.

3. Body shape on create: sections-only. `build-body` no longer prepends `# <title>`; with no `--description / --design / --acceptance` flags, the body is empty ("").

4. `knot show` rendering: unchanged. Output is `ticket/render` verbatim — YAML frontmatter then body. The title is visible as the `title:` line inside the YAML block. No synthesized H1.

5. Slug behavior: frozen at create. `save!`'s existing slug-recovery is preserved; renaming title in frontmatter does not rename the file.

6. Frontmatter key order: `title` is second, right after `id`: `id, title, status, type, priority, mode, created, updated, assignee, parent, tags, external_refs`.

7. Validation: required on create (`knot create` errors on blank title — current behavior), forgiving on read. Read sites use `(or (:title fm) "")` so an unmigrated or malformed ticket degrades gracefully instead of crashing `knot ls`.

8. Edit ergonomics: no new rename command. Title edits flow through `knot edit` → `$EDITOR` → reload. No `knot rename` or `--title` flag added in this change.

Assumptions:
- Single-user / local-CLI tool; graceful read-side fallback is preferred over hard schema validation.
- Stale slugs after rename are already the de-facto behavior and acceptable.
- The body-only-sections shape is acceptable in editors; no UX adjustment needed for empty bodies.

## Acceptance Criteria

- `src/knot/cli.clj`: `build-frontmatter` accepts and emits `:title` at position 2 (right after `:id`); `build-body` no longer prepends `# <title>`; an empty section list yields an empty body string.
- `src/knot/output.clj`: `extract-title` is deleted; all 7 call sites (`inverse-line`, `ls` table value-of, JSON projections, dep-tree text and JSON nodes, prime in-progress line, `show-json`) read `(or (:title (:frontmatter ticket)) "")`.
- `knot create "X"` writes a frontmatter that contains `title: X` between `id` and `status`, and a body that does not contain any H1.
- `knot create "X"` with no `-d/--design/--acceptance` writes an empty body (no synthesized header).
- `knot show <id>` output is unchanged in shape: closing `---` followed by the body verbatim, with no synthesized `# <title>`.
- Reading a ticket that lacks `title:` in frontmatter does not crash `knot ls`, `knot show`, or `knot dep tree` — it renders an empty title.
- Renaming a ticket via $EDITOR (changing only the `title:` line) does not change the filename on disk.
- All existing tickets in `.tickets/` and `issues/` are manually migrated in the same PR (H1 stripped from body, `title:` added to frontmatter at position 2).
- `CHANGELOG.md` notes the breaking storage-format change.
- Tests in `output_test.clj` that synthesize a fake body with `# title` are updated to set `:title` in frontmatter instead.
