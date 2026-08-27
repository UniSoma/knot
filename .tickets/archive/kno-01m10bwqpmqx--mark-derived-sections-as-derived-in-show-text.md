---
id: kno-01m10bwqpmqx
title: Mark derived sections as derived in show text
status: closed
type: task
priority: 3
mode: afk
created: '2026-08-27T01:03:33.076736075Z'
updated: '2026-08-27T01:53:07.206041496Z'
closed: '2026-08-27T01:53:07.206041496Z'
tags:
- show
- sections
- render
- body-write-surface
acceptance:
- title: Each of the five synthesized sections in knot show text carries a provenance comment naming its source field and write path; a blank line separates the body from the derived block
  done: true
- title: show --json output is byte-identical to before
  done: true
- title: CONTEXT.md defines Derived section; the knot skill carries the fields-not-body rule with the five names and the near-synonym warning
  done: true
- title: A test pins the render
  done: true
links:
- kno-01m10bvypjbq
- kno-01m10bwqkraj
- kno-01m10bwqsrk9
---

## Description

`knot show` prints the YAML frontmatter, the stored body, and then five synthesized sections — `## Acceptance Criteria`, `## Blockers`, `## Blocking`, `## Children (d/t)`, `## Linked` — at the same heading level as body sections, with nothing marking them as computed, and with the AC block glued to the body's last line. A reader cannot tell what is stored from what is derived, and acceptance appears twice in one render (frontmatter and checklist). `--json` already separates them: the five are structured keys and `sections` is body-only.

Give each derived section a provenance line directly under its heading, an HTML comment naming the source field and the write path — for example `<!-- from frontmatter deps; edit with knot dep -->`, `<!-- from frontmatter acceptance; edit with --add-ac / --ac -->`, `<!-- inverse of other tickets' deps; not editable here -->`. Invisible in a markdown viewer, plain in the terminal where agents read. Separate the derived block from the body with a blank line. `--json` is untouched.

Same change: CONTEXT.md gains a `Derived section` term beside `Body section`; the knot skill states that show synthesizes these five from fields and that anything one would write under those names (or near-synonyms such as Blocked by, Parent document, Depends on) goes through the field's flag instead. Nothing in prime. Rejected: one separator before the block (missed by a reader landing mid-render), moving the derived block above the body, dropping the YAML from show.

## Notes

**2026-08-27T01:53:07.206041496Z**

Each of the five derived sections in knot show text now carries an HTML provenance comment under its heading naming the source field and the write path, and a single blank line separates the stored body from the derived block in both the body-ends-with-newline and body-does-not cases. acceptance/render-section emits its own comment; output/inverse-section-order became triples carrying one per section, so the comment sits under the Children heading even with its (d/t) progress suffix. show --json is untouched: the strings are reachable only from show-text, and the byte-level json-contract pins still hold. Blocking's comment names 'knot dep on the blocked ticket' rather than the ticket's 'not editable here', since AC1 requires every comment to name a write path — matching the wording kno-01m10bwqkraj landed in cli/reserved-section-owners. CONTEXT.md gains Derived section beside Body section; the skill's Notes and revisions carries the fields-not-body rule with the five names and the undetected near-synonyms.
