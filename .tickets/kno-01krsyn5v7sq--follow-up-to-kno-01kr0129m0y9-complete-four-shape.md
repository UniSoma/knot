---
id: kno-01krsyn5v7sq
title: 'Follow-up to kno-01kr0129m0y9: complete four-shape test coverage + value-flag-map name shadow'
status: open
type: task
priority: 3
mode: afk
created: '2026-05-17T03:11:12.733351900Z'
updated: '2026-05-17T03:11:12.733351900Z'
acceptance:
- title: value-flag-map (src/knot/main.clj:174-179) no longer shadows clojure.core/name; bare (name x) inside the fn body is safe; clj-kondo clean.
  done: false
- title: 'Parameterised test exercises all four argv shapes (single-line dash, double-dash, alias-shaped, multi-line) across the AC #4 flag surface from kno-01kr0129m0y9, OR a comment documents why a representative subset is equivalent.'
  done: false
- title: --dep and --link have at least one create-time test with a dash-leading value; assertion confirms the value survives intact (in :deps/:links or in the error message).
  done: false
- title: --remove-tag and --remove-ac have at least one update-time test with a dash-leading value; assertion confirms the matching item is actually removed and the normaliser does not reject.
  done: false
- title: bb test green; clj-kondo --lint src test clean.
  done: false
links:
- kno-01kr0129m0y9
---

## Description

Code review of kno-01kr0129m0y9 (commit 4dbd61f) found four small gaps worth closing as one follow-up sweep: (1) AC #2 four-shape coverage is only fully exercised on `--acceptance` on `create`; the rest of the AC #4 flag surface receives 1-2 shapes. (2) `--dep` and `--link` are not exercised with dash-leading values, even though they flow through a separate `extract-rel-order` walk in src/knot/main.clj:309-330. (3) `--remove-tag` and `--remove-ac` are not exercised under dash-leading values (only the add branch is). (4) `value-flag-map` (src/knot/main.clj:174-179) destructures `:keys [name alias coerce body?]`, shadowing `clojure.core/name`; the author works around it with explicit `clojure.core/name` calls, but a future bare `(name x)` edit will silently misbehave.

## Design

**name shadow rename (src/knot/main.clj:174-179).** Rename the destructured `name` binding to `flag-name` (or destructure `:keys [alias coerce body?] :as flag` and use `(:name flag)`). Drop the now-redundant `clojure.core/name` qualifications.

**Four-shape coverage.** Add a parameterised deftest that walks the full AC #4 flag surface from kno-01kr0129m0y9 against the four argv shapes (`"- text"`, `"--text"`, `"-x"`, `"- one\n- two"`). Acceptable to assert the value survives via `knot show <id>` text inspection for create/update flags, and via `--json` exit/output for list-filter flags. If a representative subset is genuinely sufficient, document the equivalence reasoning in a comment so future reviewers don't re-flag it.

**`--dep` / `--link` coverage.** `extract-rel-order` is an *observing* walk (reads tokens but does not consume them), so dash-leading values should pass through. Confirm with one create-time test: `knot create x --dep -bogus` either rejects with the dash-leading id surfaced verbatim in the error, or — for the lenient `--dep` path — round-trips into `:deps`. Same for `--link`.

**`--remove-tag` / `--remove-ac` coverage.** Mirror the existing `--add-tag` / `--add-ac` dash-leading round-trip tests against the remove branch. The `normalize-tag-delta-values` / `normalize-ac-delta-values` paths run after merge, so the test should confirm a dash-leading remove value (a) is not rejected by the normaliser and (b) actually removes the matching item when the ticket originally had one.
