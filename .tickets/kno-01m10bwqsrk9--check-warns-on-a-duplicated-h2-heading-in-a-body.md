---
id: kno-01m10bwqsrk9
title: check warns on a duplicated H2 heading in a body
status: open
type: task
priority: 3
mode: afk
created: '2026-08-27T01:03:33.176198586Z'
updated: '2026-08-27T01:09:29.674892696Z'
tags:
- check
- sections
- body-write-surface
acceptance:
- title: 'knot check emits warning :duplicate_section naming the heading for any body where the same ## heading appears more than once'
  done: false
- title: check --help lists the code wherever codes are enumerated
  done: false
- title: 'A test pins the warning and its absence on a body whose repeated heading is ### or deeper'
  done: false
links:
- kno-01m10bvypjbq
- kno-01m10bwqkraj
- kno-01m10bwqpmqx
---

## Description

Until the heading guard lands, `update --description` on a body whose Description carried its own `## ` subsections replaced only the prefix, so several tickets in the reporting project now hold two or three copies of their description under repeated headings. Nothing self-heals and nothing finds them.

`knot check` gains a warning code `:duplicate_section`: the same `## ` heading text appearing more than once in one body, live or archived. The message names the heading and the ticket. Repair is `update --body` or `knot edit` with git as undo — no automatic dedup, since which copy to keep is a judgment the tool cannot make.
