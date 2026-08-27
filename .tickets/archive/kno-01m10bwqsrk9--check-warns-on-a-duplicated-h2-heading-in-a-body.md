---
id: kno-01m10bwqsrk9
title: check warns on a duplicated H2 heading in a body
status: closed
type: task
priority: 3
mode: afk
created: '2026-08-27T01:03:33.176198586Z'
updated: '2026-08-27T01:53:07.076506959Z'
closed: '2026-08-27T01:53:07.076506959Z'
tags:
- check
- sections
- body-write-surface
acceptance:
- title: 'knot check emits warning :duplicate_section naming the heading for any body where the same ## heading appears more than once'
  done: true
- title: check --help lists the code wherever codes are enumerated
  done: true
- title: 'A test pins the warning and its absence on a body whose repeated heading is ### or deeper'
  done: true
links:
- kno-01m10bvypjbq
- kno-01m10bwqkraj
- kno-01m10bwqpmqx
---

## Description

Until the heading guard lands, `update --description` on a body whose Description carried its own `## ` subsections replaced only the prefix, so several tickets in the reporting project now hold two or three copies of their description under repeated headings. Nothing self-heals and nothing finds them.

`knot check` gains a warning code `:duplicate_section`: the same `## ` heading text appearing more than once in one body, live or archived. The message names the heading and the ticket. Repair is `update --body` or `knot edit` with git as undo — no automatic dedup, since which copy to keep is a judgment the tool cannot make.

## Notes

**2026-08-27T01:53:07.076506959Z**

ticket/duplicate-sections returns the ## headings a body carries more than once in first-appearance order, reusing body-heading-pat, so a heading repeated at ### or deeper is not one — it never started a section. check/check-duplicate-sections emits one :duplicate_section warning per repeated heading however many copies it has, over live and archived bodies, pointing at update --body or knot edit with git as the undo. Run against this repo it found the 5 real specimens the ticket predicted: 1 live (kno-01kzxmbta84f) and 4 archived, three of them the doubled ## Description the old --description write left behind. Follow-up kno-01m10epnepqs covers slug-equal headings, which concatenate in body-sections but are not compared as duplicates.
