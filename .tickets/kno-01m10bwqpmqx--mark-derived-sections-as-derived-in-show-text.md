---
id: kno-01m10bwqpmqx
title: Mark derived sections as derived in show text
status: open
type: task
priority: 3
mode: afk
created: '2026-08-27T01:03:33.076736075Z'
updated: '2026-08-27T01:09:29.557617125Z'
tags:
- show
- sections
- render
- body-write-surface
acceptance:
- title: Each of the five synthesized sections in knot show text carries a provenance comment naming its source field and write path; a blank line separates the body from the derived block
  done: false
- title: show --json output is byte-identical to before
  done: false
- title: CONTEXT.md defines Derived section; the knot skill carries the fields-not-body rule with the five names and the near-synonym warning
  done: false
- title: A test pins the render
  done: false
links:
- kno-01m10bvypjbq
- kno-01m10bwqkraj
- kno-01m10bwqsrk9
---

## Description

`knot show` prints the YAML frontmatter, the stored body, and then five synthesized sections — `## Acceptance Criteria`, `## Blockers`, `## Blocking`, `## Children (d/t)`, `## Linked` — at the same heading level as body sections, with nothing marking them as computed, and with the AC block glued to the body's last line. A reader cannot tell what is stored from what is derived, and acceptance appears twice in one render (frontmatter and checklist). `--json` already separates them: the five are structured keys and `sections` is body-only.

Give each derived section a provenance line directly under its heading, an HTML comment naming the source field and the write path — for example `<!-- from frontmatter deps; edit with knot dep -->`, `<!-- from frontmatter acceptance; edit with --add-ac / --ac -->`, `<!-- inverse of other tickets' deps; not editable here -->`. Invisible in a markdown viewer, plain in the terminal where agents read. Separate the derived block from the body with a blank line. `--json` is untouched.

Same change: CONTEXT.md gains a `Derived section` term beside `Body section`; the knot skill states that show synthesizes these five from fields and that anything one would write under those names (or near-synonyms such as Blocked by, Parent document, Depends on) goes through the field's flag instead. Nothing in prime. Rejected: one separator before the block (missed by a reader landing mid-render), moving the derived block above the body, dropping the YAML from show.
