---
id: kno-01kqa9tnd5qc
title: create flag values starting with dash trigger babashka.cli mis-parse
status: closed
type: bug
priority: 2
mode: hitl
created: '2026-04-28T15:02:54.117024754Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-04-28T20:20:07.609752815Z'
tags:
- cli
- parsing
acceptance:
- title: '`bb knot create "T" --acceptance "- [ ] item"` succeeds and writes the bullet item under `## Acceptance Criteria` in the body'
  done: false
- title: Same for `--description` and `--design` when their values start with `-`
  done: false
- title: Existing flags (`--type`, `--priority`, `--tags`, etc.) still parse identically
  done: false
- title: 'Test: an end-to-end `run-knot` integration test creating a ticket whose acceptance contains `- [ ]` items'
  done: false
- title: 'Stretch: explore whether the same workaround / pre-processor can apply to other flags accepting freeform text (e.g. `--summary`, `add-note` text)'
  done: false
---

## Reproduction Steps

```
bb knot create "T" --acceptance "- [ ] some item"
```

Fails with `Coerce failure: cannot transform (implicit) true to long`.

The `=` form fails the same way:

```
bb knot create "T" --acceptance="- [ ] some item"
```

## Root Cause

`babashka.cli/parse-args` splits the option value on whitespace before binding it. When the value starts with a dash (e.g. `- [ ] foo bar`), it sees `--acceptance` as a boolean (no value), then treats `[`, `]`, `foo`, `bar` as separate flags. The downstream `:priority {:coerce :long}` entry then receives an implicit `true` and throws.

Verified directly:

```clojure
(bcli/parse-args ["--acceptance=- [ ] x"]
                 {:spec {:acceptance {} :priority {:coerce :long}}})
;; => Coerce failure on :priority
;; opts intermediate: {:  true, :[ true, :] true, :u true}
```

## Impact

`knot create --description / --design / --acceptance` cannot accept any markdown body content that begins with `- ` (i.e. a bullet list) — which is exactly the natural shape of an acceptance-criteria block. Today the workaround is to create the ticket bare, then open the file directly or use `knot edit`.

## Design

Two paths:

1. **Pre-process argv in main.clj** before handing off to `babashka.cli`. Detect any `--<flag>=<value>` whose `<value>` contains a dash-prefixed token, and rewrite the value so the parser doesn't split it. Risky — fragile against future babashka.cli changes.

2. **Skip babashka.cli for body-section flags**. Parse `--description / --design / --acceptance` ourselves in `create-handler` (consume the next argv slot verbatim), then strip them from argv before delegating the rest to `babashka.cli`. Clean separation, scoped to `create`, and forward-compatible.

Option 2 is preferred. Body-section flags are a special case — they're the only ones whose values are reasonably expected to contain dashes.

## Notes

**2026-04-28T20:20:07.609752815Z**

Pre-extract --description / --design / --acceptance (and -d alias) from argv before babashka.cli sees them, in both --flag value and --flag=value forms. Body-flag values are now consumed verbatim, so dash-prefixed bodies like '- [ ] item' survive bb-cli's whitespace splitting. Removed the body keys from create-spec since they no longer round-trip through bcli. Regression-test: create-body-flags-with-dash-prefixed-values-end-to-end-test (4 cases incl. mixed --priority/--type/--tags). All 137 tests / 1128 assertions pass.
