---
id: kno-01m10epnepqs
title: Reserved and duplicate section detection misses the forms readers actually paste
status: closed
type: bug
priority: 3
mode: afk
created: '2026-08-27T01:52:39.894425592Z'
updated: '2026-08-27T02:40:09.642201932Z'
closed: '2026-08-27T02:40:09.642201932Z'
tags:
- write-guard
acceptance:
- title: update --body and check :reserved_section catch a Children heading carrying show's (d/t) progress suffix
  done: true
- title: check :duplicate_section compares headings the way body-sections keys them, so slug-equal headings that concatenate are reported
  done: true
- title: The docstrings of ticket/reserved-sections and ticket/duplicate-sections state what they compare and how that relates to what body-sections keys on
  done: true
- title: Tests pin both forms, and pin that a heading merely containing a reserved name as a substring is still not caught
  done: true
deps:
- kno-01m10g6yep8s
---

## Description

Two narrow holes surfaced in review of the body-write-surface group, both where the string a reader actually holds differs from the string detection compares.

First: `knot show` renders the children heading with a progress suffix — `## Children (2/3)`, not `## Children`. Detection matches the bare reserved name exactly, so the rendered form an agent copies out of a show render and pastes back is neither refused by `update --body` nor flagged by `knot check`. This is precisely the motivating case in kno-01m10bwqkraj — agents write back what the render showed them — landing in the one spot where render and name diverge.

Second: `body-sections` keys sections on `derive-slug` of the heading text, but `duplicate-sections` compares the raw text. So `## Description` and `## description` concatenate into one section — the exact harm `:duplicate_section` exists to report — without raising the warning. Same for any pair differing only in case or in what the slug normalizes away.

Both were judged out of scope at the time: kno-01m10bwqkraj AC1 says 'any of the five reserved H2 names' and kno-01m10bwqsrk9 says 'the same heading text', and the implementations are faithful to both. This ticket is the intent the letter missed.

## Notes

**2026-08-27T02:40:09.642201932Z**

reserved-sections strips show's (d/t) suffix before matching reserved names; duplicate-sections groups by derive-slug, the key body-sections uses. Substring matches (Children of X) stay uncaught. Pinned at unit, check and CLI levels.
