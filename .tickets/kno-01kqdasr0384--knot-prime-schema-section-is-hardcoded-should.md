---
id: kno-01kqdasr0384
status: open
type: bug
priority: 2
mode: hitl
created: '2026-04-29T19:17:35.875652651Z'
updated: '2026-04-29T19:17:58.427493937Z'
links:
- kno-01kqdat9xssc
---

# knot prime ## Schema section is hardcoded — should reflect :statuses (and :modes) from config

## Description

The `## Schema` section in `knot prime` output is rendered from a static string constant, so projects that override `:statuses` (or `:modes`) in `.knot.edn` still see the canonical defaults in the primer.

**Concrete repro:** with `:statuses ["open" "active" "closed"]` in `.knot.edn`, `knot prime` still prints:

    Frontmatter keys: id, status (open|in_progress|closed), type, priority

**Root cause:**
- `src/knot/output.clj:386-391` defines `prime-schema-cheatsheet` as a literal string with hardcoded `(open|in_progress|closed)` and `(afk|hitl)`.
- `prime-text` (`output.clj:430-459`) emits it verbatim at line 459.
- `prime-cmd` (`src/knot/cli.clj:599-653`) never threads `:statuses` (or `:modes`) from `resolve-ctx` into the data passed to the renderer, even though `:terminal-statuses` is already plumbed.

**Scope of fix:**
1. Replace the constant with a function `(prime-schema-cheatsheet statuses modes)` that interpolates the actual configured values.
2. Update `prime-text` to read `:statuses` and `:modes` from its data arg (fall back to defaults on the no-project path).
3. Update `prime-cmd` to include `:statuses` and `:modes` (from `resolve-ctx`) in the data map.
4. Update affected tests in `test/knot/output_test.clj` and any integration test that asserts on the schema text.

**Out of scope:** the `## Commands` cheatsheet line about `knot start` and the `start-cmd` hardcoded `in_progress` target — tracked separately.
