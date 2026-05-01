---
id: kno-01kqgq9vhmvr
title: JSON envelope on read commands
status: open
type: task
priority: 1
mode: afk
created: '2026-05-01T02:53:50.004055763Z'
updated: '2026-05-01T02:53:50.004055763Z'
tags:
- v0.3
- json
- cli
- needs-triage
---

## Description

Adopt the tagged JSON envelope `{schema_version, ok, data?, error?}` as the single output shape for every `--json` read command: `list`, `show`, `ready`, `blocked`, `closed`, `prime`. This is a v0.3 break — `list/ready/blocked/closed --json` change from a bare ticket array to `{schema_version: 1, ok: true, data: [...]}`; `show --json` and `prime --json` change from a bare object to `{schema_version: 1, ok: true, data: {...}}`.

On error (unknown id, ambiguous partial id, validation failure), `ok: false` carries `{code, message, candidates?}`. Partial-id ambiguity already exits 1 with candidates listed inline; under the envelope it surfaces as `{ok: false, error: {code: "ambiguous_id", candidates: [...]}}` in JSON mode.

The `warnings: []` slot is reserved for future use; do not populate in this slice. Foundational for every other v0.3 slice that touches JSON.

## Acceptance Criteria

- [ ] All `--json` read commands wrap output in the envelope
- [ ] `schema_version` set to `1` on every command
- [ ] Error path (unknown id, ambiguous id, validation failure) emits `{ok: false, error: {code, message, candidates?}}`
- [ ] Partial-id ambiguity error has `code: "ambiguous_id"` and populates `candidates`
- [ ] Snapshot tests cover envelope shape per command (success + at least one error path)
- [ ] Existing tests updated for the new shape
- [ ] CHANGELOG entry documents the breaking change
