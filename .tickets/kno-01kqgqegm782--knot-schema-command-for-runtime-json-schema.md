---
id: kno-01kqgqegm782
title: knot schema command for runtime JSON schema introspection
status: open
type: feature
priority: 3
mode: hitl
created: '2026-05-01T02:56:22.663087106Z'
updated: '2026-05-05T01:38:54.088449090Z'
tags:
- v0.3
- cli
- json
- schema
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqbjg012
- kno-01kqgqcqmy19
- kno-01kqgqc2ks70
links:
- kno-01kqts0qxbvx
acceptance:
- title: 'Sub-decision pinned in design notes: `--json` mode output format (JSON Schema spec / simple descriptor / runnable example)'
  done: false
- title: '`knot schema` (no args) prints envelope, error-code catalogue, list of commands'
  done: false
- title: '`knot schema <command>` prints just that command''s shape'
  done: false
- title: '`knot schema --json` works for both forms'
  done: false
- title: Schemas single-sourced from a `knot.schemas` (or similar) namespace — no duplication between code and docs
  done: false
- title: Snapshot tests confirm declared schema matches runtime output for every command with `--json`
  done: false
- title: Help text covers the new command
  done: false
---

## Description

Add `knot schema [<command>]` — runtime introspection that returns the JSON envelope/data shape for any command. Single-sourced from code so the binary is the authoritative contract for any agent installed via `bbin` or similar — no GitHub round-trip, no version mismatch.

**Command shape:**

```
knot schema [<command>] [--json]
```

- No args: prints the universal envelope (`{schema_version, ok, data?, error?, meta?, warnings?}`), the error-code catalogue (with which commands emit which codes), and a list of all commands with their `data` shape.
- With a command name: just that command's success/error shape, including which error codes it can emit.
- `--json` mode emits machine-readable schema (see open sub-decision below).

**Sub-decision (resolve in this ticket):**

What does `--json` mode return?
- (a) Raw JSON Schema spec (full draft-2020-12 spec). Most expressive; verbose.
- (b) Simpler descriptor (typed key map: `{"id": "string", "status": "enum: open|in_progress|...", "deps": "list<id>"}`). Easier to read and emit; less precise.
- (c) Just a runnable example (`{"schema_version": 1, "ok": true, "data": {...example values...}}`). Cheapest; demands the reader infer types.

Recommendation: (b) — it composes well with knot's tone (small CLI surface, terse output) and gives agents enough precision to validate without pulling in JSON Schema tooling. But this is a real choice; pin it before implementation.

**Implementation:**

- Schema-per-command definitions in code (likely a `knot.schemas` namespace; values are plain maps or malli specs).
- One handler that pretty-prints them.
- Snapshot tests confirm the declared schema matches the actual runtime output of each command (the test you'd want under any docs strategy anyway).

**Conventions doc** lives separately in the README/docs slice (kno-? T10) — `schema` returns shapes, README explains *why* there's an `ok` field, what `schema_version` means, the partial-id ambiguity contract, the full error-code catalogue.
