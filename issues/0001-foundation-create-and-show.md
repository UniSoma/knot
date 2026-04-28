---
id: issue-0001
title: Foundation - create and show end-to-end
status: ready
type: hitl
blocked_by: []
parent: docs/prd/knot-v0.md
created: 2026-04-28
filename: issues/0001-foundation-create-and-show.md
---

# Foundation - create and show end-to-end

## Parent document

`docs/prd/knot-v0.md`

## What to build

The first tracer bullet: a babashka project with the eight-namespace skeleton wired together so that `knot create "title"` writes a markdown ticket with YAML frontmatter to `.tickets/<id>--<slug>.md`, and `knot show <id>` reads it back and renders the frontmatter + body to stdout. This slice locks in module boundaries, the ID format, the slug derivation, frontmatter round-trip semantics (including unknown-key preservation), and the `git config user.name` default-assignee path.

This is HITL because every later slice rests on the conventions chosen here.

## User stories covered

- 1 (run `knot create` from any subdirectory)
- 2 (short, sortable, collision-free IDs)
- 21 (git `user.name` as default assignee)
- 26 (zero-config — drop in and run, partial: defaults baked in but no `.knot.edn` parsing yet)
- 28 (commits authored by user, not Knot — established by Knot never running git write commands)
- 30 (unknown frontmatter keys preserved on round-trip)
- 47 (filenames include title slug)
- 48 (filenames do not auto-rename on title edit — slug is stable)

## Acceptance criteria

- [ ] `bb.edn` declares `clj-yaml` as the only non-bb dependency
- [ ] Eight namespaces stubbed (`knot.main`, `knot.cli`, `knot.ticket`, `knot.query`, `knot.store`, `knot.config`, `knot.output`, `knot.git`) with the responsibilities described in the PRD's Implementation Decisions section
- [ ] `knot.ticket` generates IDs in the form `<prefix>-<12-char Crockford-base32>` (10 timestamp + 2 random); prefix auto-derived from project directory name (first letter of each `[-_]`-separated segment, fallback to first 3 chars when too short)
- [ ] `knot.ticket` derives slugs from titles per PRD rules (lowercase + non-alphanum→hyphen, run-collapse, edge-trim, Unicode strip not transliterate, max 50 truncated at last hyphen ≤ 50, empty title → bare `<id>.md`)
- [ ] `knot.ticket` parse↔render round-trip preserves unknown frontmatter keys
- [ ] `knot.store/save!` writes tickets to `<tickets-dir>/<id>--<slug>.md` (default tickets-dir `.tickets`)
- [ ] `knot.git` reads `git config user.name` once per command run; falls back to nil in non-git directories
- [ ] `knot create "title"` works from any subdirectory of the project (walk-up to find `.tickets/`)
- [ ] `knot create` accepts the flags listed in the PRD: `-d/--description`, `--design`, `--acceptance`, `-t/--type`, `-p/--priority`, `-a/--assignee`, `--external-ref` (repeatable), `--parent`, `--tags`
- [ ] Body sections written only when their flag is supplied — no empty placeholders
- [ ] `knot show <id>` renders frontmatter + body (computed inverse sections deferred to slice 6)
- [ ] Tests: ID format conformance, slug derivation edge cases, parse↔render round-trip with unknown keys, integration test creating + reading a ticket
- [ ] Exit codes: 0 success, 1 failure; stdout = data, stderr = warnings/errors

## Blocked by

None - can start immediately
