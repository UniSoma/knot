---
id: kno-01kqxchq706w
title: 'knot update: add --add-ac / --remove-ac for AC list management'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-06T00:56:00.992161805Z'
updated: '2026-05-06T01:39:14.199103362Z'
closed: '2026-05-06T01:39:14.199103362Z'
tags:
- refine
- v0.3
links:
- kno-01kqsj9fy8zx
- kno-01kqxd0amhnb
acceptance:
- title: '`knot update --help` documents `--add-ac` and `--remove-ac` with semantics matching `--add-tag` / `--remove-tag` (repeatable, idempotent, exact-match).'
  done: true
- title: '`knot update <id> --add-ac "A" --add-ac "B"` appends both criteria with `done: false`, dedupes by exact title, preserves first-occurrence order.'
  done: true
- title: '`knot update <id> --remove-ac "A"` removes the matching criterion; a missing match is a no-op (exit 0).'
  done: true
- title: '`--add-ac` / `--remove-ac` compose with `--ac --done/--undone` in a single call; documented apply order is add → flip → remove.'
  done: true
- title: '`--body`''s help text warns the `## Acceptance Criteria` markdown section is display-only on write.'
  done: true
- title: '`--ac`''s help text points to `--add-ac` / `--remove-ac` for non-flip operations.'
  done: true
- title: Coverage in `cli_test.clj` (option-spec/unit) and `integration_test.clj` (end-to-end via `bb`).
  done: true
---

## Description

`knot update` exposes asymmetric ergonomics across list-typed frontmatter fields:

| Field            | Replace               | Add           | Remove           | Per-item flip                |
|------------------|-----------------------|---------------|------------------|------------------------------|
| `tags`           | `--tags`              | `--add-tag`   | `--remove-tag`   | —                            |
| `external_refs`  | `--external-ref`      | —             | —                | —                            |
| `acceptance`     | —                     | —             | —                | `--ac` + `--done`/`--undone` |

Acceptance criteria can only be flipped, never added or removed non-interactively. Agents reading `knot update --help` see `--ac` and reasonably assume parity with `--add-tag` / `--remove-tag`; when parity isn't there, the next reach is tool-shopping (`knot edit --help`, hand-editing the file), defeating `knot update`'s purpose. Multiple agents have hit this in practice.

Two compounding frictions:

1. **No discoverability link to `knot edit`.** When AC list management isn't in `update`'s flags, `--help` doesn't point to the editor escape hatch.
2. **`--body` silently doesn't sync the `## Acceptance Criteria` markdown section to frontmatter.** The body section reads as authoritative but is display-only on write. No warning in `--body`'s help text. Agents rewriting the body in good faith end up with stale `acceptance:` frontmatter.

Affected:

- `src/knot/cli.clj` — `update-cmd` and option spec
- `src/knot/help.clj` — `update` subcommand help text
- `test/knot/cli_test.clj` + `test/knot/integration_test.clj` — coverage

## Design

Primary fix — add CRUD flags matching the tag pattern:

- `--add-ac "<title>"` — repeatable, idempotent (no-op if a criterion with the exact title already exists). Appends with `done: false`.
- `--remove-ac "<title>"` — repeatable, idempotent (no-op if no exact match). Removes the matching item.
- Composable with `--ac --done/--undone` in the same call. Apply order: add → flip → remove (so a flip referring to a just-added criterion works in one call).

Secondary fixes (bundled into the same ticket; small, doc-level):

- `--body`'s help text gains a one-line warning that the body's `## Acceptance Criteria` section is display-only on write.
- `--ac`'s help text gains a pointer: "use `--add-ac` / `--remove-ac` to add or remove criteria."

Out of scope: bidirectional body↔frontmatter AC sync (a separate design call about which side is canonical when they disagree).

## Acceptance Criteria

- [ ] `knot update --help` documents `--add-ac` and `--remove-ac` with semantics matching `--add-tag` / `--remove-tag` (repeatable, idempotent, exact-match).
- [ ] `knot update <id> --add-ac "A" --add-ac "B"` appends both criteria with `done: false`, dedupes by exact title, preserves first-occurrence order.
- [ ] `knot update <id> --remove-ac "A"` removes the matching criterion; a missing match is a no-op (exit 0).
- [ ] `--add-ac` / `--remove-ac` compose with `--ac --done/--undone` in a single call; documented apply order is add → flip → remove.
- [ ] `--body`'s help text warns the `## Acceptance Criteria` markdown section is display-only on write.
- [ ] `--ac`'s help text points to `--add-ac` / `--remove-ac` for non-flip operations.
- [ ] Coverage in `cli_test.clj` (option-spec/unit) and `integration_test.clj` (end-to-end via `bb`).

## Notes

**2026-05-06T01:39:14.199103362Z**

Shipped --add-ac / --remove-ac on knot update (repeatable, idempotent, exact-match), composing with --ac --done/--undone in apply order add -> flip -> remove. --body now warns the ## Acceptance Criteria section is display-only on write; --ac points to the new deltas for non-flip ops. Coverage: cli_test/integration_test/help_test. Bundled skill in sync. Commit d3795bb.
