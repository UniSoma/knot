---
id: kno-01m128tds21z
title: Guard the error- and check-code catalogues in json-protocol.md against drift
status: in_progress
type: chore
priority: 2
mode: afk
created: '2026-08-27T18:48:20.514835666Z'
updated: '2026-08-27T18:48:20.753372004Z'
acceptance:
- title: test/knot/doc_codes_test.clj collects every error code emitted in main.clj and asserts each has a row in json-protocol.md's error table, and vice versa
  done: false
- title: The same test covers check codes emitted in check.clj against the check-code table, both directions
  done: false
- title: The docstring states the guard pins the code set, not per-command attribution
  done: false
- title: bb test and clj-kondo pass
  done: false
---

## Description

`open_children` was contract-tested (`json_contract_test.clj`) for months and absent from the error-code table in `.claude/skills/knot/references/json-protocol.md`; nothing could notice. Same class as the flag-drift guard (`doc_flags_test.clj`, kno-01kzxmbta84f).

Decision (grill 2026-08-27): a grep-style test, new file `test/knot/doc_codes_test.clj`, both catalogues, both directions. Error codes are `{:code "..."}` string literals in `src/knot/main.clj`; check codes are `:code <keyword>` in `src/knot/check.clj`. Every emitted code must appear as a row in the matching table, and every row must name an emitted code. Accepted limit, stated in the test docstring: the guard pins the code *set*, not the per-command attribution column.
