---
id: kno-01kqn0mtsvpq
title: Make argument parsing strict
status: closed
type: task
priority: 2
mode: afk
created: '2026-05-02T18:54:04.603784207Z'
updated: '2026-05-06T17:19:23.259727992Z'
closed: '2026-05-06T17:19:23.259727992Z'
tags:
- refine
- v0.3
links:
- kno-01kqxd0amhnb
- kno-01kqys6tvsdr
acceptance:
- title: :restrict? true set on every value of help/registry (the 16 currently lenient entries flipped)
  done: true
- title: edit-handler routes through (spec :edit) instead of literal {:spec {}}
  done: true
- title: Registry-invariant test asserts (every? :restrict? (vals help/registry))
  done: true
- title: 'Smoke test: knot show <id> --bogus exits 1 with Unknown option on stderr'
  done: true
- title: .claude/skills/knot/SKILL.md states that every command rejects unknown flags
  done: true
- title: CHANGELOG [Unreleased] -> Changed entry added, mirroring the v0.3 :create strict-flip paragraph
  done: true
- title: bb test passes; clj-kondo --lint src test baseline preserved (no new errors/warnings)
  done: true
---

## Notes

**2026-05-02T18:55:22.817137784Z**

For example, `--tag` passed to `knot create` does not errors

**2026-05-02T21:18:46.761399166Z**

`:create` was made strict in kno-01kqgqa7wnep (--afk/--hitl removal), since silent absorption was the load-bearing failure mode the removal needed to address. One fewer surface for this ticket to convert. Remaining unrestricted commands per `grep -L "restrict?" src/knot/help.clj`-ish: `:list`, `:show`, `:status`, `:start`, `:close`, `:reopen`, `:dep`, `:dep/tree`, `:undep`, `:link`, `:unlink`, `:add-note`, `:edit`, `:update`, `:check`, `:init` — verify and tighten as the ticket prescribes.

**2026-05-06T17:19:23.259727992Z**

Shipped strict-parsing across the CLI. Flipped :restrict? true on the 16 lenient registry entries (init, show, list, status, start, close, reopen, dep, dep/tree, undep, link, unlink, add-note, edit, update, check) — every command now rejects unknown flags with exit 1 and 'Unknown option: :<name>' on stderr instead of silently absorbing typos. Bundled fix: edit-handler routes through (spec :edit) instead of the literal {:spec {}} so the entry's :restrict? actually fires. Coverage: registry-invariant subtest in help-test/registry-shape-test pins (every? :restrict? (vals help/registry)); show-rejects-unknown-flag-test covers the end-to-end smoke path on knot show <id> --bogus. Bundled SKILL.md + CHANGELOG synced. Tests 326/4186/0; lint baseline preserved (4 errors / 5 warnings, all pre-existing). Commit 6df6f82.

## Description

Many commands silently absorb unrecognized flags (e.g. `--tag` instead of `--tags`), giving no error and no signal to the user. The mechanism for strict parsing already exists: `:restrict? true` on a registry entry surfaces as `babashka.cli`'s `:restrict true`, rejecting unknown flags with exit 1 and `Unknown option: :<name>` on stderr.

Today it is set on 7 entries (`:prime`, `:create`, `:ready`, `:blocked`, `:closed`, `:info`, `:migrate-ac`). Flip it on the remaining 16 so the contract is uniform across the CLI.

Lenient today: `:init`, `:show`, `:list` (`ls`), `:status`, `:start`, `:close`, `:reopen`, `:dep`, `:dep/tree`, `:undep`, `:link`, `:unlink`, `:add-note`, `:edit`, `:update`, `:check`.

## Design

**Scope.** Flip `:restrict? true` on the 16 lenient entries. Positional-arity rejection, mutually-exclusive-flag enforcement, and type-coercion strictness are explicitly **out of scope** — file follow-up tickets if wanted.

**Error UX.** Keep `babashka.cli`'s default `Unknown option: :<name>` message untouched. Message-quality work (drop the keyword colon, name the command, JSON envelope routing, "did you mean" suggestions) is owned by `kno-01kqxd0amhnb` and stays there.

**`:edit` outlier.** `edit-handler` at `src/knot/main.clj:716` bypasses `(spec :edit)` with a hard-coded `{:spec {}}`. Switch it to `(spec :edit)` so the entry flows through `derive-spec` uniformly. Bundled in the same commit — has no independent test-driven justification.

**Tests.** Registry invariant: assert `:restrict?` is true on every value of `help/registry`. Plus one smoke test (`knot show <id> --bogus` exits 1 with `Unknown option` on stderr). The existing suite covers happy paths; if `:restrict true` rejects anything a current test passes, `bb test` surfaces the regression.

**Docs.** One user-facing sentence in `.claude/skills/knot/SKILL.md` ("every command rejects unknown flags"). CHANGELOG `[Unreleased]` → `Changed` entry mirroring the v0.3 `:create` strict-flip paragraph at `CHANGELOG.md:261-263`, generalized to "all commands".

**Rollout.** Single commit. Independent of `kno-01kqxd0amhnb` — landing this ticket first expands that bug's failure surface to `:update` but doesn't create new behavior, just promotes an existing failure mode to uniform reach.

**Known interaction (no action).** After the flip, `knot update <id> --description` (bare body flag, no value) surfaces as `Unknown option: :description` because `extract-body-flags` leaves the bare token in argv when `tail` is empty, then `babashka.cli` rejects it under restrict. Misleading text but a strict improvement over today's silent argv-token theft. Owned by the linked bug if anyone bothers.
