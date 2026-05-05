---
id: kno-01kqgqa7wnep
title: Remove --afk and --hitl shortcut flags from knot create
status: closed
type: chore
priority: 3
mode: afk
created: '2026-05-01T02:54:02.645480566Z'
updated: '2026-05-05T01:38:54.088449090Z'
closed: '2026-05-02T20:45:22.418074018Z'
tags:
- v0.3
- cli
- cleanup
- needs-triage
acceptance:
- title: '`--afk` flag removed from `knot create`'
  done: false
- title: '`--hitl` flag removed from `knot create`'
  done: false
- title: Help text updated to remove the shortcut entries
  done: false
- title: Init-stub `.knot.edn` template has a comment under `:modes` forbidding shortcut flags ("use --mode <value> always; do not add per-mode shortcut flags")
  done: false
- title: Tests updated; any test using `--afk`/`--hitl` migrated to `--mode <value>`
  done: false
- title: CHANGELOG entry notes the breaking change
  done: false
---

## Description

Remove the `--afk` and `--hitl` shortcut flags from `knot create`. They are aliases for `--mode afk` / `--mode hitl` but bake the canonical mode names into CLI parsing — same hardcoded-canonical-config-literals pattern fixed in kno-01kqdat9xssc (`:active-status`).

On a project that customizes `:modes` to drop `afk` or `hitl` (e.g. `:modes ["solo" "team"]`), the shortcut flags reference modes the project does not have. They either error confusingly or silently write a value validation will reject.

After this slice, `--mode <value>` is the only path to set the mode on `knot create`. Add a comment to the `:modes` section of the init stub forbidding new mode-shortcut flags as `:modes` grows.

Pre-1.0 break window — no deprecation cycle.

## Notes

**2026-05-02T20:45:22.418074018Z**

Removed --afk and --hitl shortcut flags from `knot create`. `--mode <value>` is now the only path to set the mode. Hardcoded-canonical-config-literals pattern eliminated — projects that customize :modes (e.g. ["solo" "team"]) no longer face shortcut flags referencing modes they don't have. Pre-1.0 break window, no deprecation cycle.

Changes: dropped :afk/:hitl from the :create flag spec in help.clj; removed the resolve-mode helper and the dissoc :afk :hitl shim in main.clj/create-handler (collapsed to a direct read of :mode); init-stub :modes block now carries a comment naming the per-mode-shortcut anti-pattern explicitly. README and SKILL.md updated; CHANGELOG entry under Changed (BREAKING) with the migration recipe.

TDD: wrote 13 RED assertions across three sites first (help_test pinning the registry; integration_test pinning end-to-end behavior + help-text omission; cli_test pinning the init-stub comment), watched them fail with feature-missing reasons, then drove them green. The five legacy create-mode-flags-end-to-end-test scenarios were collapsed into create-mode-shortcut-flags-removed-test, which exercises the new behavior (--afk is silently absorbed, mode falls through to default-mode). Six unrelated integration tests that used --afk/--hitl as test fixtures migrated to --mode <value>.

Final suite: 262 tests / 2344 assertions / 0 failures (1 net new test, but coverage shifted from "shortcut translates correctly" to "shortcut is gone, --mode is sole path"). clj-kondo baseline unchanged (4 errors / 5 warnings, all pre-existing). Live smoke: knot init now writes the new :modes comment block; knot create --help shows --mode only (no --afk/--hitl); knot create "Smoke" --afk produces mode: hitl (default-mode), proving the shortcut is inert.

**2026-05-02T21:19:18.925616087Z**

Post-review follow-on (same uncommitted change set):

- Added `:restrict? true` to the `:create` registry entry. Rationale per code review: silent absorption of `--afk`/`--hitl` was a defect of *this* slice, not a separable hardening concern — a user with stale muscle memory typing `--afk` should get a clear "Unknown option: :afk" on stderr (exit 1), not a quietly-wrong ticket. Brings `:create` in line with the listing commands (`prime`/`ready`/`blocked`/`closed` already restrict).
- Integration test `create-mode-shortcut-flags-removed-test` rewritten to pin the parser-rejection contract (exit 1 + "Unknown option" + flag name on stderr) instead of pinning silent absorption.
- `create-body-flag-not-consumed-end-to-end-test` updated similarly: previously asserted "--body silently dropped" (defensive), now asserts "--body rejected as unknown option" (strict). Stronger guarantee.
- Tightened the init-stub `:modes` comment per review nit: "(--<mode-name>)" placeholder rather than the literal `(--afk, --hitl)` (which named flags that don't exist in a fresh project), and rephrased "as :modes grows" to "the values of :modes" to also cover the customize-via-replacement case.
- Added pointer comment between `create-has-no-mode-shortcut-flags-test` (registry-level) and `create-mode-shortcut-flags-removed-test` (end-to-end) so the coupling is documented.
- Added notes to kno-01kqn0mtsvpq (one fewer surface for the strict-parsing sweep) and kno-01kqgqf4aw4j (PRD doc-debt at docs/prd/knot-v0.md:111).
- Removed unused `[knot.ticket]` require from integration_test.clj (last reference dropped when the body-flag test moved off `parse`).

Final suite: 262 tests / 2344 assertions / 0 failures. Lint baseline unchanged (4 errors / 5 warnings, all pre-existing). Smoke-tested live: `create --afk` and `create --typoed-flag` both exit 1 with "Unknown option" on stderr; `create --mode afk` and `create --help` still work.
