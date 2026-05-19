---
id: kno-01kryx804m7g
title: knot delete --cascade — reference cleanup path
status: closed
type: feature
priority: 3
mode: afk
created: '2026-05-19T01:22:44.489792907Z'
updated: '2026-05-19T14:10:20.051817263Z'
closed: '2026-05-19T14:10:20.051817263Z'
tags:
- needs-triage
deps:
- kno-01kryx7ey6m0
acceptance:
- title: '`knot delete <id> --cascade` cleans a referrer that holds the target in `:deps` and deletes the file'
  done: true
- title: '`knot delete <id> --cascade` cleans a referrer that holds the target in `:links` and deletes the file'
  done: true
- title: '`knot delete <id> --cascade` cleans a referrer that holds the target in `:parent` and deletes the file'
  done: true
- title: Cascade rewrites archived referrers, not just live ones; their `:updated` is bumped
  done: true
- title: One referrer holding the target in multiple fields (e.g. `:deps` and `:links`) has every field cleaned in a single save; `cleaned` row lists both fields
  done: true
- title: '`--cascade` on a leaf ticket is a silent no-op (no audit lines, `cleaned: []` under `--json`)'
  done: true
- title: After a successful cascade delete, `knot check` reports zero `unknown_id` issues attributable to the deleted id
  done: true
- title: 'Stderr emits one `knot delete: cleaned <id> (:field, ...)` line per cleaned referrer; stdout still prints only the removed path'
  done: true
- title: '`--json` success envelope populates `data.cleaned: [{id, fields:[...]}]` for every cleaned referrer'
  done: true
- title: Partial-cleanup failure (simulated mid-batch write error) leaves the target file in place, emits a stderr breadcrumb, and re-running `knot delete --cascade` is idempotent (already-cleaned referrers are no-ops)
  done: true
- title: '`.claude/skills/knot/SKILL.md` is updated in the same commit to document `--cascade`; `bb test` and `clj-kondo --lint src test` both clean'
  done: true
---

## Description

Build on the leaf-only slice. `knot delete <id> --cascade` performs the cleanup that the bare command refuses: rewrites every referrer (live + archive) to drop the deleted id from `:deps`/`:links` and dissoc `:parent` when it pointed there, dropping the field key entirely when the resulting list is empty (mirrors `undep`/`unlink`). Bumps `:updated` on each cleaned referrer.

Write ordering: referrers first, target file last. On partial-cleanup failure (one referrer write throws), emit a stderr breadcrumb naming what was already committed and abort before unlinking the target — so we never leave `unknown_id` errors against a no-longer-existent target.

Stdout stays a single line (removed path), so the contract is identical between bare and cascade. Per-referrer audit goes to stderr (`knot delete: cleaned <id> (:field, ...)`). Under `--json`, `data.cleaned` is populated with `[{id, fields:[...]}]` rows.

`--cascade` on a leaf (zero referrers) is a silent no-op, not an error — matches the `--force` precedent on non-gated transitions.

Pinned by ADR 0008.

## Notes

**2026-05-19T14:10:20.051817263Z**

Shipped: `knot delete <id> --cascade` rewrites every referrer (live + archive) — drops the target from :deps/:links, dissocs :parent, drops the field key when emptied, bumps :updated on each save. Write order is referrers first (alphabetical by id), target last; mid-batch save failure emits a stderr breadcrumb and re-throws before unlinking, so re-running --cascade is idempotent. Stderr emits one `knot delete: cleaned <id> (:field, ...)` line per cleaned referrer; stdout stays the single removed path. --json populates data.cleaned with [{id, fields:[...]}]. --cascade on a leaf is a silent no-op. 17 new tests (cli + integration); SKILL.md, json-protocol.md, CHANGELOG.md updated in the same change.
