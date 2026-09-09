---
id: kno-01m21aczttph
title: Move the knot skill source to resources/ and guard the committed copy
status: closed
type: chore
priority: 2
mode: afk
created: '2026-09-08T20:12:24.794135626Z'
updated: '2026-09-09T18:51:15.536740629Z'
closed: '2026-09-09T18:51:15.536740629Z'
parent: kno-01m21abe2vqs
tags:
- docs
- testing
acceptance:
- title: resources/knot/skill/ holds SKILL.md, references/{lifecycle,graph,json,autonomous,writes}.md and agents/openai.yaml; the old three reference files are gone
  done: true
- title: A bb test asserts .claude/skills/knot/ is byte-identical to resources/knot/skill/ and fails when either side changes alone
  done: true
- title: doc_flags_test and doc_codes_test glob resources/knot/skill/ instead of .claude/skills/knot/ and still pass
  done: true
- title: No topic file starts with YAML frontmatter; SKILL.md keeps its name/description frontmatter unchanged
  done: true
links:
- kno-01m21zqt72bh
external_refs:
- git:4fa232fba93aa746c985e65735bd77ba9f9413a2
---

## Description

Make `resources/knot/skill/` the single source of the agent skill. Layout: `SKILL.md` (frontmatter + the `intro` body), `references/<topic>.md` for `lifecycle`, `graph`, `json`, `autonomous`, `writes`, and `agents/openai.yaml`. Merge today's three references into the topics they belong to (`lifecycle-gates.md` → `lifecycle`, `listing-filters-and-columns.md` → `graph`, `json-protocol.md` → `json`). Move the "Notes and revisions" / render-is-not-body / replace-vs-delta material into `writes`; "Working autonomously" into `autonomous`.

`.claude/skills/knot/` in this repo stays committed as a copy so a fresh clone and CI keep working. Topic files carry no frontmatter (they print verbatim from `knot help <topic>` and must work as references in any harness).

Reword the skill's "when the two disagree, the CLI wins — tell the user the skill has drifted" to point at `knot skill install` as the refresh.

## Notes

**2026-09-09T18:51:15.536740629Z**

resources/knot/skill/ is now the source of the agent skill: SKILL.md, references/{lifecycle,graph,json,autonomous,writes}.md, agents/openai.yaml. The three old references were renamed to their topic names with only their internal cross-links changed; writes.md and autonomous.md were split out of SKILL.md, which keeps a compressed pointer where each block was. The drift sentence now names `knot skill install` as the refresh but keeps "tell the user the skill has drifted" — that command lands in kno-01m21ad02qt0, so deleting the only working recovery would have left the branch with no valid action until then.

.claude/skills/knot/ stays committed as a byte-identical copy. test/knot/skill_copy_test.clj compares file sets and bytes in both directions (verified red on a one-sided edit and on a one-sided extra file) and additionally pins the seven-file layout, that only SKILL.md opens with YAML frontmatter, and that its name/description block survives. doc_flags_test and doc_codes_test glob resources/knot/skill/ instead of the copy. README.md and docs/prd/knot-v0.md follow the json-protocol.md -> json.md rename; CHANGELOG and ADRs 0015/0016 were left alone as historical records.

Left for the next tickets: the H1 titles still read "Listing filters and columns" / "Knot JSON protocol" / "Lifecycle gates" rather than their topic names — worth retitling when `knot help <topic>` starts printing them (kno-01m21aczyqmx). No bb task re-copies the tree; `knot skill install` is that affordance.

497 tests / 5776 assertions, 0 failures. clj-kondo 0 errors / 0 warnings.
