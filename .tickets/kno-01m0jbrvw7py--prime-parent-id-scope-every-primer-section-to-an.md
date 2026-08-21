---
id: kno-01m0jbrvw7py
title: 'prime --parent <id>: scope every primer section to an umbrella''s direct children'
status: open
type: feature
priority: 3
mode: afk
created: '2026-08-21T14:32:04.231670256Z'
updated: '2026-08-21T14:32:04.231670256Z'
parent: kno-01m0jbrv6wbe
tags:
- prime
- agents
acceptance:
- title: knot prime --parent <id> restricts all four sections to direct children of <id>, in markdown and --json
  done: false
- title: prime --parent composes with the existing filters (e.g. --mode afk)
  done: false
- title: Help for prime and the skill mention the flag in the same commit
  done: false
---

## Description

prime already takes mode/status/assignee/tag/type/priority and every section is a listing (in_progress, ready_to_close, ready, recently_closed), so --parent composes the same way it does on list/ready/blocked/closed. Filter only — no new narrative section; the umbrella's own text is show <id> --json .sections.description once the show sections ticket lands (no hard dependency). Repeatable and partial-id-resolving like the listing flag. The umbrella itself is excluded (it is not its own direct child).
