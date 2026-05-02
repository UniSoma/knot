---
id: kno-01kqgqf4aw4j
title: README and JSON protocol documentation for v0.3
status: open
type: chore
priority: 3
mode: afk
created: '2026-05-01T02:56:42.844118574Z'
updated: '2026-05-02T21:18:54.163951281Z'
tags:
- v0.3
- docs
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqegm782
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

## Acceptance Criteria

- [ ] README has a "Concurrency" section explaining the no-locking model and pointing at git + kno-01kqgqaxzx98
- [ ] `docs/json-protocol.md` exists (or equivalent README section), covering envelope shape, ok discriminator, error-code catalogue, partial-id contract, schema versioning
- [ ] Per-command `data` shapes are NOT duplicated in docs — link to `knot schema`
- [ ] At least one example per envelope variant: success / error / partial-id ambiguity / mutation with meta
- [ ] CHANGELOG v0.3 entry consolidates every breaking change in one place
- [ ] CI badge / docs links in README still work

## Notes

**2026-05-02T21:18:54.163951281Z**

PRD doc-debt to fold into v0.3 docs scope: `docs/prd/knot-v0.md:111` still names `--afk` and `--hitl` as `--mode` sugar. Those flags were removed in kno-01kqgqa7wnep. The PRD is a historical product spec, not a user reference, so the right move is probably an inline note ("history: removed in v0.3 per kno-01kqgqa7wnep") rather than an edit that loses the spec's original intent. Decision belongs to whoever picks this ticket up.
