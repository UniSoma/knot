# `knot delete` defaults to leaf-only; `--cascade` opts into reference cleanup

`knot delete <id>` removes a ticket from disk (live or archive) and is intended for junk cleanup (typo'd `create`, AI-generated duplicates, scratch tickets) and pruning long-tail archive noise. The destructive twin of `close --summary "Won't do: …"`: close keeps the file resolvable in archive and leaves every dep/link/parent reference to it valid; delete makes the id permanently unresolvable, which means surviving tickets must lose those pointers or `knot check` will flag `unknown_id` until they're patched.

We deliberately make a bare `knot delete <id>` **refuse** when any other ticket — live or archived — names this id in `:parent`, `:deps`, or `:links`. The refusal enumerates each referrer + the field, and exits 1. An explicit `--cascade` is required to opt into the reference rewrite (drop from `:deps`/`:links`, dissoc `:parent`, dropping the field key when the resulting list is empty, mirroring `undep`/`unlink`). Cascade-touched tickets get `:updated` bumped — including archived ones, because `knot check` scans the archive and a dangling-ref invariant has to beat the archive-immutability vibe. Write order is referrers first, target last; on partial-cleanup failure we emit a stderr breadcrumb and abort before unlinking the file, so we never leave `unknown_id` errors against a no-longer-existent target.

The reason to refuse-by-default rather than always cascading: dropping a ticket from someone else's `:deps` is semantically load-bearing — it can flip a downstream ticket from `blocked` to `ready` without anyone touching that ticket. That mutation should be explicit. The bare command doubles as the dry-run (it shows exactly what `--cascade` would clean up), so we don't need a `--dry-run` flag. There is no undo and no `.tickets/trash/`; `.tickets/` is git-tracked and `git checkout` is the recovery path.

## Considered options

- **Always cascade silently** — rejected. Matches what users literally ask for, but a fat-finger `delete` could silently flip multiple downstream tickets from blocked to ready, and the operator has no chance to see the blast radius before committing. The ergonomic win (one command instead of two) doesn't justify the loss of explicitness on a destructive op.
- **Always cascade, print audit, add `--dry-run`** — rejected. Same load-bearing-mutation concern as "always cascade silently", just with a separate flag to opt into safety. Inverts the right default: the safe path should be the one that takes no flag.
- **`--force` instead of `--cascade`** — rejected. `--force` in knot today means "bypass a gate" (acceptance, open-children). Here we're not bypassing a check — we're performing N additional writes against other tickets. Reusing `--force` would suggest "suppress a warning" when the actual behavior is "mutate other files". `--cascade` names the verb.
- **Refuse without enumerating referrers (terse error)** — rejected. The scan needed to detect referrers is the same scan `--cascade` performs; withholding the list forces a second command (`knot check --code unknown_id` or similar) to recover info we already have.
- **`.tickets/trash/` with a `knot restore` command** — rejected. Git already provides recovery and history. A second restore path would need its own resolution rules, its own listing surface, and would drift from the "files live in two places, archive is one of them" mental model.

## Consequences

- A new error code, `has_incoming_refs`, joins the JSON envelope vocabulary (alongside `not_found`, `ambiguous`, `cycle`, `acceptance_incomplete`, `open_children`). Carries a `referrers` payload of `[{id, field}]` rows.
- Archived tickets are now mutable by one more command (`delete --cascade`, in addition to `reopen`). The "archive is history" intuition continues to be a vibe, not an enforced invariant.
- Agents driving knot non-interactively can rely on the bare-delete refusal as a built-in safety: a script that does `knot delete X` will not silently rewrite N other tickets. Scripts that *want* the cascade must opt in.
- `knot check` post-delete is part of the contract: a successful `knot delete` (bare or `--cascade`) leaves zero `unknown_id` issues attributable to the deleted id. Tests pin this.
