---
id: kno-01m0jbrvsdrb
title: 'show --json: split the body into sections and emit structured acceptance'
status: open
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:04.140854333Z'
updated: '2026-08-21T14:32:04.140854333Z'
parent: kno-01m0jbrv6wbe
tags:
- json
- show
acceptance:
- title: 'show --json data.sections maps slugified ## headings to their markdown; preamble lands under ""'
  done: false
- title: show --json data.acceptance is the structured frontmatter list, identical in shape to list --json rows
  done: false
- title: body is unchanged; existing consumers keep working
  done: false
- title: knot.schema.json, help for show, the skill and CHANGELOG describe the new keys in the same commit
  done: false
---

## Description

show --json returns body as one string and no structured acceptance list, so an agent cannot jq a section; implementer prompts end up pointing at a ~170-line render. Additive change: data gains sections — a map keyed by the slug of each '## ' heading (description, design, user-stories, children, notes, …) to the raw markdown below it, with any preamble before the first heading under "" — and acceptance as the frontmatter list ([{title, done}]) exactly as list/ready emit it. body stays unchanged. No text-mode --section or --brief flag: a partial render is now jq on sections.

JSON shape change: help :notes for show, the skill, knot.schema.json and CHANGELOG move in the same commit.
