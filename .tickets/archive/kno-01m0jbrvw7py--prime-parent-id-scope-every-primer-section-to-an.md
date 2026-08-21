---
id: kno-01m0jbrvw7py
title: 'prime --parent <id>: scope every primer section to an umbrella''s direct children'
status: closed
type: feature
priority: 3
mode: afk
created: '2026-08-21T14:32:04.231670256Z'
updated: '2026-08-21T16:05:57.371230943Z'
closed: '2026-08-21T16:05:57.371230943Z'
parent: kno-01m0jbrv6wbe
tags:
- prime
- agents
acceptance:
- title: knot prime --parent <id> restricts all four sections to direct children of <id>, in markdown and --json
  done: true
- title: prime --parent composes with the existing filters (e.g. --mode afk)
  done: true
- title: Help for prime and the skill mention the flag in the same commit
  done: true
---

## Description

prime already takes mode/status/assignee/tag/type/priority and every section is a listing (in_progress, ready_to_close, ready, recently_closed), so --parent composes the same way it does on list/ready/blocked/closed. Filter only — no new narrative section; the umbrella's own text is show <id> --json .sections.description once the show sections ticket lands (no hard dependency). Repeatable and partial-id-resolving like the listing flag. The umbrella itself is excluded (it is not its own direct child).

## Notes

**2026-08-21T16:05:57.371230943Z**

prime --parent threads into the single criteria map feeding all four sections (in_progress, ready_to_close, ready, recently_closed), so it restricts every one to direct children in both markdown and --json — the umbrella itself and grandchildren are excluded, pinned with a grandchild fixture. Repeatable, partial-id-resolving, and it intersects with the existing filters (pinned against --mode afk). It deliberately does not reuse resolve-parent-filter!, which exits 1 through prime's try: a new resolver lets the ex-info reach prime-handler's catch so an unresolvable --parent degrades at exit 0 rather than failing a session start. That asymmetry with list/ready/blocked is pinned and recorded in prime's help :notes. Inherited nuance left alone: the degrade path ignores --json and emits the markdown fallback, as it already did for any bad argument. git:4d937b6
