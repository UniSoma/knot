---
id: kno-01kqgqf4aw4j
title: README and JSON protocol documentation for v0.3
status: closed
type: chore
priority: 3
mode: afk
created: '2026-05-01T02:56:42.844118574Z'
updated: '2026-05-05T17:48:15.412185239Z'
closed: '2026-05-05T17:48:15.412185239Z'
tags:
- v0.3
- docs
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqegm782
links:
- kno-01kqts0qxbvx
acceptance:
- title: README has a "Concurrency" section explaining the no-locking model and pointing at git + kno-01kqgqaxzx98
  done: false
- title: '`docs/json-protocol.md` exists (or equivalent README section), covering envelope shape, ok discriminator, error-code catalogue, partial-id contract, schema versioning'
  done: false
- title: Per-command `data` shapes are NOT duplicated in docs — link to `knot schema`
  done: false
- title: 'At least one example per envelope variant: success / error / partial-id ambiguity / mutation with meta'
  done: false
- title: CHANGELOG v0.3 entry consolidates every breaking change in one place
  done: false
- title: CI badge / docs links in README still work
  done: false
---

## Description

Cover the user-visible documentation surface for the v0.3 changes.

**README additions:**

- **Concurrency** section — knot does no locking; one writer per ticket at a time is the assumed model; git is the conflict-detection and undo path; if multi-agent workflows hit conflicts, see the optimistic-concurrency placeholder (kno-01kqgqaxzx98). Short — three paragraphs at most.

**`docs/json-protocol.md`** (new file, or a long README section):

- Envelope shape: `{schema_version, ok, data?, error?, meta?, warnings?}` with each field's semantics
- `ok` discriminator pattern (success vs error path)
- Per-command `data` shapes are *not* enumerated here — link to `knot schema [<command>]` (kno-01kqgqegm782) as the authoritative source
- Error-code catalogue: every code knot emits, what triggers it, what fields it carries (`candidates` for ambiguous_id, `ids` for issues from `knot check`, etc.)
- Partial-id ambiguity contract: errors loudly with `code: "ambiguous_id"`, populates `candidates`
- Schema versioning: how to interpret `schema_version`, breaking-change policy, `knot --version` is the binary version
- Examples per envelope variant (success, error, partial-id ambiguity, mutation with meta)

**CHANGELOG entry for v0.3** consolidating the breaking changes:
- Bare-array → enveloped shape on read commands
- `--afk` / `--hitl` shortcut flags removed
- `dep cycle` removed (replaced by `knot check`)
- Acceptance criteria migrated from body to frontmatter (one-shot migration runs on first invocation)
- Hardcoded `"in_progress"` replaced with `:active-status` (already shipped via kno-01kqdat9xssc, but mention in v0.3 release notes)

## Notes

**2026-05-02T21:18:54.163951281Z**

PRD doc-debt to fold into v0.3 docs scope: `docs/prd/knot-v0.md:111` still names `--afk` and `--hitl` as `--mode` sugar. Those flags were removed in kno-01kqgqa7wnep. The PRD is a historical product spec, not a user reference, so the right move is probably an inline note ("history: removed in v0.3 per kno-01kqgqa7wnep") rather than an edit that loses the spec's original intent. Decision belongs to whoever picks this ticket up.

**2026-05-05T17:41:58.227917671Z**

Implementation pass complete. Surfaces touched:

1. New `docs/json-protocol.md` — canonical v0.3 JSON contract reference. Sections: envelope shape, `ok` discriminator (with `knot check` carve-out), `meta` slot, schema versioning, partial-id contract (strict vs soft resolution table), error-code catalogue (six codes: `not_found`, `ambiguous_id`, `cycle`, `invalid_argument`, `no_project`, `config_invalid` with command matrix), per-command `data` shape tables (read + mutating), `knot check` issue-code catalogue (eleven codes), worked examples for every envelope variant (success / error / ambiguous_id / mutation+meta / health-verdict / cycle), and a recipes block. Mirrors `test/knot/json_contract_test.clj` so prose drift is caught at `bb test` time.

2. README — added top-level `## Concurrency` section between `AI-agent integration` and `Philosophy`. Three paragraphs explaining the no-locking model, git as conflict-detection/undo, and the optimistic-concurrency placeholder ticket pointer for multi-writer scenarios. Also added a one-line link from the existing JSON-output paragraph to `docs/json-protocol.md`.

3. CHANGELOG `[Unreleased]` — restructured to Keep-a-Changelog conventional ordering: Added → Changed (BREAKING) → Removed → Fixed. Merged the two fragmented `### Added` blocks into one. Added new `### Removed` section that explicitly lists `--afk`/`--hitl` flag removal and `knot dep cycle` command removal (these were buried inside BREAKING before). Added two BREAKING entries the previous block had glossed over: the AC body→frontmatter file-format migration (`knot migrate-ac` is the lift command), and `:active-status` config replacing the hardcoded `"in_progress"` (config-derived now; default behavior preserved). All four breaks the ticket Description called for are now in BREAKING in one place. Added two new bullets in `### Added` for the README Concurrency section and the new docs file.

4. `docs/prd/knot-v0.md` — addressed the doc-debt note in the ticket Notes block. Added a top-of-file `History note (v0.3)` callout explaining that the PRD is preserved as historical design rationale and that v0.3 deviations are flagged inline. Added three inline `*v0.3:*` historical notes: at the surface section (dep cycle removal, info/update/check additions, mutating --json), at the create-flags description (--afk/--hitl removal per kno-01kqgqa7wnep, --acceptance moving to frontmatter), and at the JSON-output description (envelope wrapping reversed the original "no envelope" decision). The historical spec is preserved verbatim; current contract pointers redirect to README/SKILL/json-protocol/CHANGELOG.

5. `.claude/skills/knot/SKILL.md` — synced per the AGENTS.md hard rule. Added a one-paragraph pointer at the top of the JSON section directing agents to `docs/json-protocol.md` for the canonical contract. Expanded the error-envelope paragraph to mention `invalid_argument`, `no_project`, and `config_invalid` (previously listed only the three id-resolution codes).

ACs (six items):

- README Concurrency section ✓
- docs/json-protocol.md exists with envelope/ok/error-codes/partial-id/schema-versioning ✓
- Per-command data shapes — REVISED interpretation per the won't-do note on kno-01kqgqegm782. The original AC said "link to `knot schema`"; that command was abandoned and its responsibility absorbed into this ticket. docs/json-protocol.md is now the single (non-runtime) source of per-command shape enumeration; the SKILL links there; the runtime source of truth is `test/knot/json_contract_test.clj`. No duplication.
- Examples per envelope variant ✓ (six worked examples covering all variants plus the carve-outs)
- CHANGELOG v0.3 BREAKING consolidation ✓
- CI badge + README links resolve ✓ (verified each relative path; `.github/workflows/ci.yml` exists for the badge)

Tests: 314/4055/0 (unchanged — no source code touched). Lint baseline preserved at 4 errors / 5 warnings, all pre-existing.

No source code was modified. All changes are documentation-only.

**2026-05-05T17:48:15.412185239Z**

README/JSON-protocol documentation pass for v0.3 — docs only, no source changes.

Surfaces:

- New `.claude/skills/knot/references/json-protocol.md` (moved from `docs/` so
  it travels with the bundled skill into other projects). Canonical contract
  for the v0.3 `--json` envelope: shape, `ok` discriminator (with the
  `knot check` health-verdict carve-out), `meta` slot, schema versioning,
  partial-id contract (strict-vs-soft resolution table covering the
  documented `dep`/`undep`/`unlink` `to`-side and `dep tree` tolerant-root
  asymmetries), six error codes (`not_found`, `ambiguous_id`, `cycle`,
  `invalid_argument`, `no_project`, `config_invalid`) with command matrix,
  per-command `data` shape tables (read + mutating), eleven `knot check`
  issue codes, six worked examples (success / not_found / ambiguous_id /
  mutation+meta / health-verdict / cycle), recipes block. Mirrors what
  `test/knot/json_contract_test.clj` already pins so prose drift surfaces
  at `bb test` time.

- README — new top-level `## Concurrency` section between AI-agent
  integration and Philosophy. Three paragraphs: no-locking model
  (one-writer-per-ticket assumption); git as the conflict-detection and
  undo path (also noted as the documented undo for destructive
  `update --body`); pointer to the optimistic-concurrency placeholder
  ticket for multi-writer scenarios. JSON-output paragraph now links to
  the skill reference with framing that explains the portability
  rationale.

- CHANGELOG `[Unreleased]` — restructured to Keep-a-Changelog conventional
  ordering: Added → Changed (BREAKING) → Removed → Fixed. Merged the two
  fragmented `### Added` blocks. New `### Removed` block explicitly lists
  `--afk`/`--hitl` flags (kno-01kqgqa7wnep) and `knot dep cycle` (was
  buried in BREAKING before). Two new BREAKING entries that the previous
  block had glossed: AC body→frontmatter file-format migration
  (`knot migrate-ac` lifts existing checklists), and `:active-status`
  config replacing the hardcoded `"in_progress"` (config-derived now;
  default behavior preserved). All four breaks the ticket Description
  called for are in BREAKING in one place. Added bullets for the new
  Concurrency section and the protocol reference.

- `docs/prd/knot-v0.md` — addressed the doc-debt note in the ticket Notes.
  Top-of-file `History note (v0.3)` callout explaining the PRD is preserved
  as historical design rationale; current contract pointers redirect to
  README/SKILL/json-protocol/CHANGELOG. Three inline `*v0.3:*` deviation
  markers at the surface section (dep cycle removal, info/update/check
  additions, mutating --json), at create flags (--afk/--hitl removal per
  kno-01kqgqa7wnep, --acceptance moving to frontmatter), and at JSON output
  (envelope wrapping reversed the original "no envelope" decision). The
  spec is preserved verbatim; nothing edited away.

- `.claude/skills/knot/SKILL.md` — synced per the AGENTS.md hard rule.
  Pointer at the top of the JSON section directing agents to the new
  sibling `references/json-protocol.md`. Error-envelope paragraph expanded
  to mention `invalid_argument`, `no_project`, `config_invalid`
  (previously listed only the three id-resolution codes).

ACs:

- README Concurrency ✓
- json-protocol.md exists with envelope/ok/error-codes/partial-id/
  schema-versioning ✓
- Per-command data shapes — REVISED interpretation per the won't-do
  note on kno-01kqgqegm782. The original AC said "link to `knot schema`";
  that command was abandoned and its responsibility absorbed into this
  ticket. The reference doc is now the single (non-runtime) source of
  per-command shape enumeration; SKILL links there; the executable source
  of truth remains `test/knot/json_contract_test.clj`. No duplication.
- Examples per envelope variant ✓ (six worked examples)
- CHANGELOG v0.3 BREAKING consolidation ✓
- CI badge + README links resolve ✓ (verified `.github/workflows/ci.yml`
  exists and every relative path under README resolves)

Mid-session reroute: the protocol reference initially landed at
`docs/json-protocol.md`, then moved to the skill folder so projects that
copy `.claude/skills/knot/` inherit the contract — the skill is documented
as user-portable in README's `### Skill` block, and a doc reference outside
the skill folder broke that promise.

Tests: 314/4055/0 (unchanged — no source code touched). Lint baseline
preserved at 4 errors / 5 warnings, all pre-existing.
