---
id: kno-01kqgqcqmy19
title: New knot update command for non-interactive ticket writes
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-01T02:55:24.318439621Z'
updated: '2026-05-02T19:28:38.459642999Z'
closed: '2026-05-02T19:28:38.459642999Z'
parent: kno-01kqfaqw9bfk
tags:
- v0.3
- cli
- needs-triage
deps:
- kno-01kqgq9vhmvr
- kno-01kqgqbjg012
---

## Description

Add `knot update <id>` as a sibling write command. `knot edit` keeps its single meaning ("open in `\$EDITOR`"); `update` is the non-interactive path agents and scripts use.

Resolves the parent ticket kno-01kqfaqw9bfk (expand `edit` vs add `update`?) in favor of the `update` direction.

Flag set:

Frontmatter:
- `--title <text>`
- `--tags <comma-list>`
- `--priority <0..4>`
- `--type <value>`
- `--mode <value>`
- `--assignee <handle>`
- `--parent <id>`
- `--external-ref <ref>` (repeatable)

Body (whole or sectional):
- `--description <text>` — replace `## Description` section
- `--design <text>` — replace `## Design` section
- `--acceptance <text>` — replace `## Acceptance Criteria` section (note: kno-? Q6 promotes ACs to frontmatter; this flag becomes the structured-AC list editor at that point)
- `--body <text>` — replace the whole body. Destructive; **no `--force` ceremony** — git is the documented undo path.

`--json` returns the touched ticket via the envelope from kno-01kqgq9vhmvr (data = post-update ticket).

`add-note` stays distinct and append-only — `update` is purely set/replace, not append. Do not add `--note` to `update`.

`:updated` frontmatter timestamp bumps on every successful write (re-uses `store/save!`).

## Acceptance Criteria

- [ ] `knot update <id>` command implemented
- [ ] Full flag set: `--title --tags --priority --type --mode --assignee --parent --external-ref --description --design --acceptance --body`
- [ ] `--body` works without `--force`; CHANGELOG flags it as destructive
- [ ] `--json` returns the post-update ticket via envelope
- [ ] `:updated` bumps on every write
- [ ] No `--note` flag (append remains `add-note`'s job)
- [ ] Tests cover frontmatter-only update, sectional body update, full-body replace, `--json` mode
- [ ] Help text + README updated; AFK-agent path documented

## Notes

**2026-05-02T19:28:38.459642999Z**

Implemented knot update <id> as the non-interactive sibling to knot edit. Frontmatter flags --title/--type/--priority/--mode/--assignee/--parent/--tags/--external-ref set field values; optional fields (:assignee, :parent, :tags, :external_refs) clear when given a blank/empty value. Body flags replace named sections in place: --description/--design/--acceptance. --body replaces the whole body destructively (no --force; git is the undo path) and is mutually exclusive with the sectional flags. --json returns the v0.3 success envelope wrapping the post-mutation ticket (no :meta slot since update never archives). :updated bumps on every save via store/save!. add-note stays append-only — update is purely set/replace, never append (no --note flag).

TDD: tests written first per group (frontmatter set/clear, sectional body update — in-place + append-when-missing + preserve-other-sections, whole-body replace + mutual-exclusion guard, --json envelope, not-found-on-stdout under --json, ambiguous-id throw), watched fail before implementing. New cli/update-cmd plus replace-section / update-frontmatter / update-body helpers in cli.clj. Help registry gained :update entry under :notes group; main.clj got update-handler with body-flag pre-extraction. Bundled SKILL.md kept in sync per project hard rule (intent table, tool mapping, JSON section, AFK checklist, "Write tickets only via" line, quick reference). README and CHANGELOG updated; --body flagged as destructive per AC.

Code review pass caught two important issues, both addressed before commit: (1) the global body-flag-extraction map I'd added --body to was silently swallowing knot create --body values, since extract-body-flags is also called by create-handler; refactored to take a flag-map per call site (create-body-flags for create; update-body-flags for update; union for help-requested?). Regression test pinned. (2) babashka.cli's --external-ref "" produces [""] which is not (empty?), so the cli-layer dissoc-on-empty branch never fired and "blank clears" was a documentation lie; update-handler now (remove str/blank? ...) before passing through, mirroring split-tags. New integration test pins the contract. Plus minor polish: SKILL.md "Write tickets only via" line gained knot update; replace-section docstring relaxed re: trailing-newline invariant on the no-op branch; --external-ref help text now documents the clear-by-empty syntax explicitly.

Final suite: 252 tests / 2217 assertions / 0 failures (7 new tests, 88 new assertions). clj-kondo baseline unchanged (4 errors / 5 warnings, all pre-existing). Live smoke against the binary confirmed: update --priority 0 --tags p0,smoke --description "..." --json returns the envelope; update --body x --description y exits 1 with "knot update: --body is mutually exclusive..." on stderr; update <missing> --json exits 1 with not_found envelope on stdout; update --external-ref "" clears external_refs from the data; create --body "ignored" leaves the new ticket's body empty.
