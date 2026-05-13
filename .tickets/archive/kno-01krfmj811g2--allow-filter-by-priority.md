---
id: kno-01krfmj811g2
title: Allow filter by priority
status: closed
type: task
priority: 1
mode: hitl
created: '2026-05-13T03:02:26.593842764Z'
updated: '2026-05-13T23:51:37.485178938Z'
closed: '2026-05-13T23:51:37.485178938Z'
acceptance:
- title: 'query.clj: match-criterion? gains :priority case (equality on integer :priority in frontmatter; nil priority excluded, mirrors :assignee/:mode)'
  done: true
- title: cli.clj filter-criteria and main.clj filter-opts-from-cli include :priority in the projection vector
  done: true
- title: 'help.clj: :priority flag added to :list, :ready, :blocked, :closed, :prime specs with :coerce [:long]; out-of-range values (not in 0..4) rejected at parse time with a clear message'
  done: true
- title: .claude/skills/knot/SKILL.md updated to mention --priority on the listing commands
  done: true
- title: docs/agents/issue-tracker.md filter-flag enumeration (~lines 121-126) includes --priority alongside the other set filters
  done: true
- title: CHANGELOG.md Unreleased entry for the new --priority filter
  done: true
- title: 'emacs/knot.el: priority added to knot-list--filter-keys + knot-list--filter-cli-flags; new knot-list--read-priority (completing-read over priority_range from knot info); new knot-list-filter-set-priority; new ("p" "priority" knot-list-filter-set-priority) suffix in the knot-list-filter transient'
  done: true
- title: emacs/README.md mentions the new priority filter alongside the other filter keys
  done: true
---

## Description

Add a `--priority N` filter to the listing commands so callers can scope results by priority (e.g. `knot ready --priority 0` to see only top-priority work). The flag slots into the existing filter set alongside `--status`, `--assignee`, `--tag`, `--type`, `--mode`.

### Scope

CLI surface: `list`, `ready`, `blocked`, `closed`, and `prime`. Prime is not in the original ticket body but carries the same `filter-opts-from-cli` projection (`help.clj:165-168`); omitting `--priority` there would create a quiet asymmetry, so it's included.

### Design contracts (non-obvious decisions resolved during planning)

1. **`:coerce [:long]`** — parse at the CLI boundary. Frontmatter stores priority as an integer (`config.clj:133`, `check.clj:60`); string-vs-int mismatch would silently match nothing. Mirrors `create --priority`'s existing `:coerce :long` (`help.clj:181`).
2. **Reject out-of-range at parse time** — `--priority 5` errors with a clear message. *Unlike* `--type widget` or `--status nonsense`, which are permissive and just return zero rows. Justification: priority is a closed enum in this codebase (config/check enforce 0..4 everywhere), unlike type/status/mode which are config-extensible.
3. **Range syntax deferred** — only enumeration (`--priority 0 --priority 1`) for now. No `0-2` / `<=2` micro-grammar; symmetric with the other filters. Re-evaluate if real usage groans.
4. **No short alias** — `-p` is bound to `--priority` on `create`, but none of the sibling listing filters have short aliases. Within-command consistency wins; revisit only if we add short aliases for the whole set at once.
5. **Nil priority excluded from matches** — mirrors `:assignee`/`:mode`. A ticket without a `:priority` field never matches `--priority N`. Loud-empty-result over silent-config-fallback.
6. **Emacs filter is single-value-per-key** — same shape as the existing `--tag`/`--type`/`--mode` filter suffixes; the transient infrastructure already only models one value per dimension.

### Reference sites to mirror (copy the shape; don't invent)

- **CLI flag spec**: the `--tag` / `--type` / `--mode` entries in `help.clj` for each of `:list`, `:ready`, `:blocked`, `:closed`, `:prime`. Note `:coerce [:long]` is different from those (which are `:coerce []`).
- **Match case**: the `:tag` / `:type` / `:mode` arms of `case k` in `query.clj:match-criterion?` (`src/knot/query.clj:30`). `:priority` is an equality match: `(contains? vs (:priority fm))`.
- **Projection vectors**: `cli.clj:filter-criteria` (`src/knot/cli.clj:939`) and `main.clj:filter-opts-from-cli` (`src/knot/main.clj:304`) — add `:priority` to both keyword vectors.
- **Emacs reader**: `knot-update--read-priority` (`emacs/knot.el:2193`) already does the right thing — `completing-read` over `priority_range` from `knot info`. The new `knot-list--read-priority` should follow its structure (with empty-input-clears, since this is a filter).
- **Emacs filter constants**: `knot-list--filter-keys` (`emacs/knot.el:596`) and `knot-list--filter-cli-flags` (`emacs/knot.el:599`). Transient suffix lands on `p` (free among `m t s T a l A C`).

### Out of scope

- Range / comparator syntax (see contract 3).
- Short alias on listing commands (see contract 4).
- Treating nil priority as `:default-priority` at match time (see contract 5).
- Multi-value-per-filter in the emacs transient (orthogonal; would touch every filter, not just priority).

## Notes

**2026-05-13T23:51:37.485178938Z**

Shipped --priority N (repeatable, 0..4) on knot list / ready / blocked / closed / prime. Out-of-range rejected at parse time with a clear message ('--priority must be 0..4; got N'). Wired through query/match-criterion? :priority arm, both filter-projection vectors, prime-cmd criteria, and emacs knot-list-filter transient (p suffix + knot-list--read-priority). CHANGELOG Unreleased entry + SKILL.md + issue-tracker.md + emacs/README.md updated. 4431 assertions / 0 failures; lint baseline preserved.
