---
id: kno-01m0jbrvsdrb
title: 'show --json: split the body into sections and emit structured acceptance'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:04.140854333Z'
updated: '2026-08-21T15:21:44.505294084Z'
closed: '2026-08-21T15:21:44.505294084Z'
parent: kno-01m0jbrv6wbe
tags:
- json
- show
acceptance:
- title: 'show --json data.sections maps slugified ## headings to their markdown; preamble lands under ""'
  done: true
- title: show --json data.acceptance is the structured frontmatter list, identical in shape to list --json rows
  done: true
- title: body is unchanged; existing consumers keep working
  done: true
- title: knot.schema.json, help for show, the skill and CHANGELOG describe the new keys in the same commit
  done: true
---

## Description

show --json returns body as one string and no structured acceptance list, so an agent cannot jq a section; implementer prompts end up pointing at a ~170-line render. Additive change: data gains sections — a map keyed by the slug of each '## ' heading (description, design, user-stories, children, notes, …) to the raw markdown below it, with any preamble before the first heading under "" — and acceptance as the frontmatter list ([{title, done}]) exactly as list/ready emit it. body stays unchanged. No text-mode --section or --brief flag: a partial render is now jq on sections.

JSON shape change: help :notes for show, the skill, knot.schema.json and CHANGELOG move in the same commit.

## Notes

**2026-08-21T15:21:44.505294084Z**

show --json data now carries sections (slug of each '## ' heading -> raw markdown, preamble under "") and a pinned acceptance list. sections is assoc'd in show-payload, not jsonify-ticket, so list --json and mutator envelopes are untouched and body is byte-identical — pinned by a subprocess guard written before the change. data.acceptance needed no production change; it already passed through from frontmatter and is now pinned against list --json. knot.schema.json is the frontmatter schema and is out of scope for an envelope key: bb gen:schema verified no diff. Surfaces moved in the same commit: show help :notes + jq example, json-protocol.md, CHANGELOG. git:d26194a
