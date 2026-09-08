---
id: kno-01m21aczttph
title: Move the knot skill source to resources/ and guard the committed copy
status: open
type: chore
priority: 2
mode: afk
created: '2026-09-08T20:12:24.794135626Z'
updated: '2026-09-08T20:12:24.794135626Z'
parent: kno-01m21abe2vqs
tags:
- docs
- testing
acceptance:
- title: resources/knot/skill/ holds SKILL.md, references/{lifecycle,graph,json,autonomous,writes}.md and agents/openai.yaml; the old three reference files are gone
  done: false
- title: A bb test asserts .claude/skills/knot/ is byte-identical to resources/knot/skill/ and fails when either side changes alone
  done: false
- title: doc_flags_test and doc_codes_test glob resources/knot/skill/ instead of .claude/skills/knot/ and still pass
  done: false
- title: No topic file starts with YAML frontmatter; SKILL.md keeps its name/description frontmatter unchanged
  done: false
---

## Description

Make `resources/knot/skill/` the single source of the agent skill. Layout: `SKILL.md` (frontmatter + the `intro` body), `references/<topic>.md` for `lifecycle`, `graph`, `json`, `autonomous`, `writes`, and `agents/openai.yaml`. Merge today's three references into the topics they belong to (`lifecycle-gates.md` → `lifecycle`, `listing-filters-and-columns.md` → `graph`, `json-protocol.md` → `json`). Move the "Notes and revisions" / render-is-not-body / replace-vs-delta material into `writes`; "Working autonomously" into `autonomous`.

`.claude/skills/knot/` in this repo stays committed as a copy so a fresh clone and CI keep working. Topic files carry no frontmatter (they print verbatim from `knot help <topic>` and must work as references in any harness).

Reword the skill's "when the two disagree, the CLI wins — tell the user the skill has drifted" to point at `knot skill install` as the refresh.
