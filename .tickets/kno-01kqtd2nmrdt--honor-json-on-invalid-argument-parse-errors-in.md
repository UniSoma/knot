---
id: kno-01kqtd2nmrdt
title: Honor --json on invalid_argument parse errors in `knot info`
status: open
type: bug
priority: 2
mode: afk
created: '2026-05-04T21:07:33.144840213Z'
updated: '2026-05-04T21:07:38.183155796Z'
tags:
- v0.3
- cli
- json
links:
- kno-01kqsjaxb6dy
---

## Description

When `knot info` is invoked with both an unknown flag and `--json`, the parse error is reported as plain stderr text instead of the standard v0.3 JSON envelope. This breaks the JSON contract for scripted callers.

Live reproduction:

    $ knot info --bogus --json
    knot info: Unknown option: :bogus

Root cause is at `src/knot/main.clj:687`:

    (info-emit-error! false "invalid_argument" (.getMessage ^Exception parsed))

The `false` is hardcoded for the JSON flag, so `--json` is never honored on the parse-failure path. Other handlers (`link`, `unlink`, `update`) already route `invalid_argument` through a JSON envelope when `--json` appears in argv — `info` is the outlier.

Surfaced during code review of kno-01kqsjaxb6dy (commit 12911ed). Spec-wise this is not strictly required (the acceptance criteria only enumerate `no_project` / `config_invalid` for JSON errors), but the inconsistency is a sharp edge for any agent or script catching info errors.

## Design

Replicate the pattern from the sibling handlers in `src/knot/main.clj` (link/unlink/update): cheaply detect `--json` from the raw argv before delegating to `bcli/parse-args`, and pass that flag through to `info-emit-error!` on the parse-failure branch. Existing JSON-envelope error helpers can be reused — no new shape.

## Acceptance Criteria

- `knot info --bogus --json` emits a JSON envelope with `schema_version: 1`, `ok: false`, `error.code: "invalid_argument"`, and the parser message in `error.message`
- Exit code remains `1` on the parse-failure path
- Plain `knot info --bogus` (no `--json`) still emits stderr text as today
- Implementation pattern matches the existing argv-based `--json` detection used by link/unlink/update handlers
- Test coverage: a unit or integration test pins the JSON-envelope shape on the parse-failure path
