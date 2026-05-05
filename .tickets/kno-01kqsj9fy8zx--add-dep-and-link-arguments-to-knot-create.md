---
id: kno-01kqsj9fy8zx
title: Add --dep and --link arguments to `knot create`
status: open
type: feature
priority: 2
mode: afk
created: '2026-05-04T13:19:25.128614479Z'
updated: '2026-05-05T01:38:54.088449090Z'
acceptance:
- title: '`knot create --help` documents repeatable long-form `--dep <id>` and `--link <id>` flags, one id per occurrence, and calls out that `--dep` is lenient on missing targets while `--link` is strict.'
  done: false
- title: '`knot create "T" --dep <existing> --dep <missing>` succeeds, canonicalizes resolved dep ids, preserves unresolved deps verbatim, and dedupes duplicates after resolution while preserving first-occurrence order.'
  done: false
- title: '`knot create "T" --link <a> --link <b>` succeeds, writes reciprocal links on every touched ticket, and leaves archived targets archived.'
  done: false
- title: '`knot create "T" --dep X --link X` is allowed and records both relationships.'
  done: false
- title: Repeating the same dep or link, including equivalent partial/full ids that resolve to the same ticket, does not create duplicate entries.
  done: false
- title: 'Any strict `--link` failure aborts the command before ticket creation. Plain-text mode reports `knot create: ...`; `--json` returns a structured error envelope.'
  done: false
- title: If several strict relationship inputs are bad, the surfaced error is the first failing one in left-to-right CLI order.
  done: false
- title: 'Basic create validation still wins: a missing title fails before relationship resolution.'
  done: false
- title: Successful plain-text `create` still prints only the new ticket path.
  done: false
- title: Successful `create --json` still returns only the created ticket, adds no new `meta`, and includes the final `deps` and `links` state.
  done: false
- title: A simulated multi-file write failure during reciprocal-link application fails loudly and attempts rollback rather than silently leaving success state.
  done: false
---

## Description

Add repeatable `--dep <id>` and `--link <id>` flags to `knot create` so a ticket can enter the graph fully wired in one command.

`--dep` means the new ticket depends on the target. `--link` means the new ticket is symmetrically linked to the target. This should be sugar over the existing graph model, not a second relationship system.

Preserve the current `create` success contracts so scripts and agents can adopt the new flags without changing how they consume stdout or `--json`.

## Design

## CLI surface

- Add long-form, repeatable `--dep <id>` and `--link <id>` flags to `knot create`.
- Accept exactly one ticket id per flag occurrence. Use repetition for multiples; do not add comma-list syntax.
- Document both flags in `knot create --help`, including the different missing-target behavior.

## Relationship semantics

- `knot create "T" --dep A` means the new ticket depends on `A`.
- `knot create "T" --link A` means the new ticket is linked to `A`, and `A` must be updated to link back to the new ticket.
- Dedupe within `deps` and within `links` after resolution, preserving first-occurrence order.
- Do not dedupe across dimensions; `--dep A --link A` is valid and records both relationships.
- Accept partial ids for both flags.
- For `--dep`:
  - resolve unique matches to canonical full ids;
  - keep unresolved targets verbatim as forward refs;
  - fail on ambiguous targets.
- For `--link`:
  - require every target to resolve uniquely;
  - fail on missing or ambiguous targets.
- Archived tickets are valid targets for both flags. Updating an archived linked ticket must not reopen or move it.
- Do not add special support for self-reference against the just-created ticket.
- No cycle check is needed for `--dep` during create; a brand-new node with only outgoing deps cannot close a cycle under this contract.

## Validation, failure, and write model

- Validation order is:
  1. basic create validity (`title`, parser-valid flags);
  2. relationship preflight;
  3. writes.
- When several strict relationship inputs fail, surface the first failure in left-to-right CLI order.
- Non-JSON failures should use the `knot create:` prefix.
- `knot create --json` should emit structured error envelopes for relationship failures (`ambiguous_id`, `not_found`, `invalid_argument`).
- Command-level behavior should be atomic from the user's point of view:
  - if strict preflight fails, create nothing;
  - if a later reciprocal-link write fails, attempt best-effort rollback and fail loudly;
  - do not claim true cross-file transactional guarantees that the current file-based store cannot provide.
- Linked target tickets should not receive automatic notes; only relationship data and `updated` should change.

## Output contracts

- Successful plain-text `create` should still print only the new ticket path.
- Successful `create --json` should still return only the created ticket under `.data`, with no new `meta`.
- The returned created ticket must reflect the final post-relationship state, including populated `deps` and `links`.

## Likely touch points

Expect changes in the `create` help/spec surface, CLI arg parsing, `cli/create-cmd`, and the related help/unit/integration coverage. Keep `.claude/skills/knot/SKILL.md` in sync with the shipped CLI contract.
