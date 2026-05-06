---
id: kno-01kqys929mdy
title: 'knot prime: modernize directive and runtime sections'
status: closed
type: chore
priority: 2
mode: afk
created: '2026-05-06T13:57:43.336891533Z'
updated: '2026-05-06T22:46:27.072313158Z'
closed: '2026-05-06T22:46:27.072313158Z'
tags:
- p2
- v0.4
acceptance:
- title: Hitl preamble replaces the current 'When the user says...' block with the 8-row intent table (ready / show / list / start / add-note / update / close / dep) plus the modernized 'for less-common ops (info / check / link / reopen / --json shapes / partial-id contract)' skill pointer.
  done: false
- title: afk preamble inserts a 'knot update <id> ...' step between add-note and close, adds an explicit 'never knot edit (interactive only, fails without TTY)' anti-pattern, and updates the skill pointer to drop 'lifecycle' (subsumed by the explicit verbs).
  done: false
- title: '## Commands cheatsheet block removed entirely; prime-commands-cheatsheet and any references deleted from src/knot/output.clj.'
  done: false
- title: Ready row format becomes 'id  type  mode  pri  title' (5 cols); type column added; missing fields render as '-'.
  done: false
- title: In Progress row format becomes 'id  type  mode  pri  age  title' (6 cols); age renders relative :updated as Nd/Nw/Nm; the binary [stale] text prefix is removed from text output; missing :updated renders '-' in the age column.
  done: false
- title: :prime-stale? flag and stale:true field on data.in_progress[] JSON entries retained when :updated >= 14d (documented stale-flag asymmetry preserved).
  done: false
- title: Recently Closed entries truncate summary at the first paragraph boundary (\n\n) at display time, with additional hard char-cap truncation (280 chars) on long single paragraphs; append '(see knot show <id>)' when truncation occurs; JSON data.recently_closed[].summary keeps the full string.
  done: false
- title: 'Per-section nudges mode-conditioned: hitl unchanged; afk drops the ready-nudge entirely; afk in-progress-nudge rephrased to remove the ''user'' reference (''Finish your in-progress work before grabbing new tickets.'').'
  done: false
- title: '## Project section unchanged: prefix, name (when set), live, archive — same four lines.'
  done: false
- title: 'prime --json payload shape unchanged: data.in_progress[] / data.ready[] / data.recently_closed[] keep their existing keys; no schema_version bump; no new fields.'
  done: false
- title: .pi/extensions/knot-prime.ts not modified (markdown-only consumer; smaller output is a pure win).
  done: false
- title: '.claude/skills/knot/SKILL.md scanned for references to the [stale] prefix or ## Commands cheatsheet shape; updated if any drift remains.'
  done: false
- title: README.md scanned for prose documenting prime's sections; updated if drifted.
  done: false
- title: Existing prime tests in knot.output-test, knot.cli-test, knot.integration-test updated to match the new content (preamble strings, row formats, nudge text, cheatsheet absence).
  done: false
- title: 'New tests cover: type column appears in ready row; age formatter boundaries (1d / 13d / 14d / 2w / 6w / 100d); summary truncation at paragraph boundary AND at the 280-char hard cap; afk mode drops ready-nudge; ## Commands cheatsheet absent in both modes; prime --json key set parity with pre-change envelope.'
  done: false
- title: bb test passes; clj-kondo --lint src test preserves the 4-error / 5-warning baseline (all pre-existing).
  done: false
- title: CHANGELOG entry added under [Unreleased]/Changed describing the directive overhaul, new column formats, and summary truncation.
  done: false
links:
- kno-01kqe9ytd40z
- kno-01kqn3swv94c
- kno-01kqzkhpc244
---

## Description

`knot prime`'s directive content has drifted as v0.3 added `update`,
`info`, `check`, structured AC, and the JSON envelope. The current
preamble's intent table misses the agent-write verb (`update`); the
`## Commands` cheatsheet duplicates `SKILL.md` but with stale coverage
(no `update` / `check` / `info` / `--json`); `Recently Closed`
multi-paragraph summaries dominate prime's vertical real estate; the
binary `[stale]` prefix carries less information than a relative-age
column would.

This ticket renovates prime end-to-end against v0.3, with a clear
division of labor: prime carries a small, stable directive (CLI is
the contract + the most common verb mappings) and defers the long
tail to the bundled `knot` skill, which is the canonical reference.

Out of scope (deferred to existing tickets):

- Tags column on prime / list / ready / blocked rows — owned by
  kno-01kqn3swv94c (linked).
- Age column on list / ready / blocked listings — owned by
  kno-01kqe9ytd40z (linked); this ticket only adds the age column to
  prime's `## In Progress` section. The listings ticket adopts the
  same formatter when it lands.
- `prime --json` schema_version bump — explicit non-goal; this is a
  display refresh, no contract changes.
- AC progress column on `## In Progress` rows (`2/5 done`) — defer to
  a follow-up if it proves useful.

## Design

## Directive content (preamble)

**hitl preamble** — replace the current 7-row "When the user says..."
block with this 8-row table:

```
"what's next?" / "what should I work on?"        → `knot ready` (add `--mode afk` for agent-runnable only)
"show me <id>" / "tell me about <id>"            → `knot show <id>` (resolves partial ids; works on archive too)
"any pending bugs?" / "what's tagged X?"          → `knot list --type bug` (also: --tag, --mode, --assignee, --status, --limit)
"let's tackle <id>" / "start working on <id>"    → `knot start <id>`
"note that..." / "FYI..." mid-task               → `knot add-note <id> "..."`
"retitle / retag / change priority / set ..."    → `knot update <id> --title|--tags|--priority|--assignee|--description ...`
"I'm done" / "shipped" / "let's close this"      → `knot close <id> --summary "..."`
"blocked on <other>" / "what's blocking <id>?"   → `knot dep <current> <other>` / `knot dep tree <id>`
```

Closing pointer becomes:

> For less-common ops (`info` / `check` / `link` / `reopen` / `--json` shapes / partial-id contract), invoke the `knot` skill.

**afk preamble** — keep the autonomous flow checklist shape; insert
one `update` step between `add-note` and `close`, and add an explicit
"never `knot edit`" anti-pattern:

```
knot update <id> --priority 0 --tags p0,auth   patch frontmatter or named body sections (non-interactive — never use `knot edit`, it opens $EDITOR and will fail without a TTY)
```

Modernized skill pointer parenthetical: drop "lifecycle" (subsumed by
the explicit verbs in the checklist), keep "graph ops, JSON shapes,
partial-id resolution".

## Sections

**`## Commands` cheatsheet** — removed entirely. `prime-commands-cheatsheet`
and any references deleted from `output.clj`.

**`## Project`** — unchanged. Same four lines (prefix, name, live, archive).

**`## Ready`** — row format becomes `id  type  mode  pri  title` (5
cols, type added). Missing fields render as `-`.

**`## In Progress`** — row format becomes `id  type  mode  pri  age  title`
(6 cols, type added, age replaces the binary `[stale]` prefix). Age
formatter renders `:updated`'s relative age as `Nd` for <14d, `Nw` for
14d–6w, `Nm` for >6w, etc. Missing `:updated` renders `-`. The
`:prime-stale?` flag is still set on the ticket map under the hood
and `stale: true` still appears on `data.in_progress[]` JSON entries
when `:updated >= 14d` — the documented stale-flag asymmetry is
preserved.

**`## Recently Closed`** — text renderer truncates summary at the
first paragraph boundary (`\n\n`). If the first paragraph still
exceeds a hard char cap (suggested 280), additionally truncate at the
cap. Append `(see knot show <id>)` whenever truncation occurs. JSON
keeps the full summary on `data.recently_closed[].summary`.

## Per-section nudges

Mode-conditioned:

- **hitl mode**: in-progress-nudge and ready-nudge unchanged.
- **afk mode**: ready-nudge dropped entirely (the afk preamble's flow
  checklist already covers it). In-progress-nudge rephrased to remove
  the "user" reference: "Finish your in-progress work before grabbing
  new tickets."

## JSON contract

No changes. `data.in_progress[]` / `data.ready[]` / `data.recently_closed[]`
keep their existing keys; no new fields; no `schema_version` bump.
`stale: true` asymmetry preserved. `references/json-protocol.md`
needs no edits (verify on the way out).

## Files touched

- `src/knot/output.clj` — preambles, row formatters, section
  renderers, nudge constants, summary truncation. Bulk of the diff.
- `src/knot/cli.clj` — small: nothing structural (the truncation
  lives in the renderer; recently-closed projection still emits the
  full summary so JSON has it).
- `test/knot/output_test.clj`, `test/knot/cli_test.clj`,
  `test/knot/integration_test.clj` — update existing prime tests;
  add new tests per AC #15.
- `CHANGELOG.md` — entry under `[Unreleased]/Changed`.
- `.claude/skills/knot/SKILL.md` — scan for stale references
  (`[stale]` prefix shape, `## Commands` cheatsheet); update if found.
- `README.md` — scan for prime-section prose; update if drifted.
- `.pi/extensions/knot-prime.ts` — not modified.
- `test/knot/json_contract_test.clj` — not modified (no JSON
  shape change).

## Verification

- `bb test` passes.
- `clj-kondo --lint src test` preserves the 4-error / 5-warning
  baseline (all pre-existing).
- Manual: run `knot prime` and `knot prime --mode afk` and eyeball
  the new output against the design.
- Manual: `knot prime --json | jq keys` shows the same key set as
  before.

## Notes

**2026-05-06T22:46:27.072313158Z**

knot prime directive and runtime sections modernized end-to-end against v0.3.

Preambles:
- HITL: 7-row 'When the user says...' table → 8-row mapping that adds the agent-write verb (knot update) and an explicit show/list/dep tree row, with inline filter annotations on the read-row (--mode afk, --tag, etc.). Closing pointer rephrased to 'For less-common ops (info / check / link / reopen / --json shapes / partial-id contract), invoke the knot skill' so the directive frames the skill as the long-tail reference rather than duplicating its contents.
- AFK: knot update step inserted between add-note and close, tagged with 'never use knot edit, opens $EDITOR, fails without TTY' anti-pattern. Skill pointer drops 'lifecycle' (subsumed by the autonomous-flow checklist).

## Commands cheatsheet retired entirely (function + section). Preamble's intent table plus bundled knot skill cover the same ground without per-session token cost.

Row formats:
- In Progress: id  type  mode  pri  age  title (6 cols). New output/format-age-days helper renders relative :updated as Nd (<14d), Nw (14d-6w, floor by 7), or Nm (>6w, floor by 30); missing :updated renders as -. The binary [stale] text prefix is retired; the age column carries the staleness signal in human-readable form. cli.clj's prime-in-progress-tickets now decorates each ticket with :prime-age-days alongside :prime-stale?.
- Ready: id  type  mode  pri  title (5 cols), type column inserted. Missing fields render as -.

Recently Closed summaries truncate at the first paragraph boundary (\n\n) at display time, with a 280-char hard cap on long single paragraphs. When truncation fires, the line ends with ' (see knot show <id>)'. prime --json data.recently_closed[].summary keeps the full untruncated string (text/JSON asymmetry is intentional).

Per-section nudges are mode-conditioned. HITL unchanged. AFK drops the Ready nudge entirely and rephrases the In Progress nudge to 'Finish your in-progress work before grabbing new tickets.' (drops the 'user' reference).

prime --json payload shape unchanged: same keys on data.in_progress[] / data.ready[] / data.recently_closed[], no schema_version bump, no new fields. stale:true preserved on JSON in_progress entries (asymmetry documented).

Implementation: split prime-ticket-line into prime-ready-line + prime-in-progress-line; prime-section now takes a row-fn parameter. New truncate-prime-summary helper. .pi/extensions/knot-prime.ts and test/knot/json_contract_test.clj not modified (markdown-only consumer + no JSON shape change).

TDD via 9 vertical slices (RED→GREEN per slice): hitl preamble, afk preamble, drop cheatsheet, ready row, age formatter, in-progress row, summary truncation, mode-conditioned nudges, docs+CHANGELOG. New tests: prime-text-hitl-preamble-rows-test, prime-text-afk-preamble-shape-test, prime-text-commands-cheatsheet-removed-test, prime-text-ready-row-format-test, format-age-days-test, prime-text-in-progress-row-format-test, prime-text-recently-closed-section-test (truncation cases), prime-text-mode-conditioned-nudges-test. Existing prime-cmd-stale-in-progress-test rewritten to assert age column instead of [stale]. Two cheatsheet-only tests removed (prime-text-active-status-cheatsheet-test, prime-text-close-shows-summary-flag-test). Section-delimiter sites updated from ## Commands → ## Recently Closed / (count out). README.md prime prose refreshed; CHANGELOG entry under [Unreleased]/Changed; SKILL.md scanned (no drift — [stale] prefix and ## Commands shape were not referenced).

bb test 329/4234/0; src/ lint clean (0 errors / 1 pre-existing warning); test/ lint baseline preserved (4 pre-existing errors / 4 warnings, 1 fewer than baseline). Manual smoke (knot prime, knot prime --mode afk, knot prime --json | jq keys) confirms preamble shifts, 6/5-col rows, no Commands section, mode-conditioned nudges, and unchanged JSON envelope. Commit: 9d6ad3b.
