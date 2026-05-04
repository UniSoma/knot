---
id: kno-01kqsm8xgjf3
title: Allow to add/remove specific tags in `knot update`
status: open
type: task
priority: 2
mode: afk
created: '2026-05-04T13:54:03.410876783Z'
updated: '2026-05-04T23:52:19.078313394Z'
---

## Description

Today, `knot update --tags <comma-list>` only does whole-list **replace**, and `--tags ""` clears the field. There is no way to apply a tag delta — to add or remove specific tags — without first reading the current list, computing the merge, and writing the merged list back.

That round-trip is awkward in scripts (read JSON → manipulate → write) and in autonomous agent workflows (a single `update` call should be enough to express a tag delta). Add `--add-tag <t>` and `--remove-tag <t>` to `knot update` so callers can express deltas directly without prior knowledge of the current tag set.

Scope is `knot update` only. `knot create` is out of scope (separate concern; create has no prior state to delta against).

## Design

**Flag surface.** Two new repeatable singular flags, modeled on the existing `--external-ref` shape:

- `--add-tag <tag>` — add `<tag>` to the ticket's tag set. Repeatable.
- `--remove-tag <tag>` — remove `<tag>` from the ticket's tag set. Repeatable.

Rejected alternatives:
- Repeatable comma-list (`--add-tags p0,auth`) — adds a third naming family alongside singular `--external-ref` and plural `--tags`.
- Delta syntax inside `--tags` (`--tags "+p0,-stale"`) — leading `-` clashes with `bcli/parse-args`; overloads a flag whose existing semantics are "replace".
- Separate `knot tag` subcommand — breaks the "all set/replace mutations live on `update`" contract.

**Composition with `--tags`.** Mutually exclusive. Mixing replace and delta on the same call is semantically muddy (e.g. `--tags p0,auth --add-tag stale` is exactly equivalent to `--tags p0,auth,stale`), so allowing it adds zero expressive power but lets users write self-contradictory commands. Mirrors the existing `--body` ⊥ `--description/--design/--acceptance` precedent in `cli.clj:607–612`.

**Cross-flag conflict.** Same tag in both `--add-tag` and `--remove-tag` → reject (exit 1). Almost certainly a user/script bug; surfacing it loud beats picking an arbitrary precedence rule. Within a single direction (`--add-tag p0 --add-tag p0` or `--remove-tag stale --remove-tag stale`), silent dedupe — set semantics, idempotent.

**Per-tag idempotency.**
- `--add-tag <t>` where `<t>` is already present → silent no-op on the tag set; the save still happens and `:updated` still bumps (consistent with existing `save!` behavior — `:updated` bumps on every save regardless of content delta).
- `--remove-tag <t>` where `<t>` is absent → silent no-op on the tag set; same save/bump behavior.
- Set semantics: tags are a set, so `S ∪ {x}` and `S \ {x}` are well-defined regardless of prior membership.
- Empty resulting list clears the `:tags` field (consistent with existing `--tags ""`).

**Value validation.**
- Trim surrounding whitespace (mirrors `split-tags` in `main.clj:112–119`).
- Reject blank/empty values with exit 1.
- Reject values containing `,` with exit 1 — preserves the round-trip invariant that every tag can be expressed via `--tags`.

**Result ordering.** Preserve existing tag order; remove tags drop in place; add tags append at the end in `--add-tag` flag order. Mirrors how `--tags` already preserves comma-list order via `(vec tags)`. Stable diffs; no imposed ordering convention.

**Errors.** Mutual-exclusion conflict, cross-flag conflict, blank value, and comma-in-value all `throw` an `ex-info` from `update-cmd` with a descriptive message. The existing handler in `main.clj:540–582` surfaces this as either `die` (stderr + exit 1) or a `{ok:false, error:{code:"invalid_argument", message}}` envelope under `--json` — no new error code needed.

**JSON envelope.** No change. Successful runs return the existing v0.3 touched-ticket envelope; the post-mutation `:tags` reflects the merged set.

## Acceptance Criteria

- `knot update <id> --add-tag <t>` adds `<t>` to the ticket's tag set; idempotent if already present.
- `knot update <id> --remove-tag <t>` removes `<t>`; idempotent if absent.
- Both flags are repeatable; same flag with the same value across repeats silently dedupes.
- `--add-tag <t1> --remove-tag <t1>` (overlap) → exit 1, descriptive stderr; `--json` emits `{ok:false, error:{code:"invalid_argument", message}}`.
- `--tags ...` combined with `--add-tag ...` or `--remove-tag ...` → exit 1, descriptive stderr; same `invalid_argument` envelope under `--json`.
- `--add-tag ""` (or whitespace-only) → exit 1, `invalid_argument`.
- `--add-tag "p0,auth"` (comma in value) → exit 1, `invalid_argument`. Same for `--remove-tag`.
- Resulting tag list preserves existing order; removes drop in place; adds append at end in `--add-tag` flag order.
- Empty resulting tag list clears the `:tags` frontmatter key (consistent with `--tags ""`).
- Successful runs save the ticket and bump `:updated`, even when the tag set is unchanged (e.g. all ops were no-ops). Consistent with existing `save!` contract.
- `--json` returns the v0.3 touched-ticket envelope with the post-mutation `:tags`.
- Surfaces updated in the same commit:
  - `src/knot/cli.clj` — `update-frontmatter` / `update-cmd` honor the new flags and validations.
  - `src/knot/main.clj` — `:update` spec adds `:add-tag` / `:remove-tag` with `:coerce []`; handler normalizes (trim, blank-reject, comma-reject) and forwards.
  - `src/knot/help.clj` — `:update` registry entry: new flags documented, at least one example.
  - `.claude/skills/knot/SKILL.md` — Notes/Editing section updated to mention tag deltas.
  - `CHANGELOG.md` — entry under `[Unreleased]`.
- Tests (TDD; run `bb test`):
  - cli_test: tag-set merge semantics (add, remove, idempotent add, idempotent remove, dedupe within direction, ordering preservation, clear-when-empty).
  - integration_test: end-to-end CLI invocations covering each acceptance bullet, including stderr and `--json` envelope shapes for every error path.
  - help_test: `:update` parity set still matches; new flags appear in help text.
- Lint baseline unchanged: `clj-kondo --lint src test` reports the existing 4 errors / 5 warnings only.
