---
id: kno-01m0jbrvfyxd
title: Address acceptance criteria by 1-based ordinal on --ac and --remove-ac; make --ac repeatable
status: closed
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:03.838787164Z'
updated: '2026-08-21T15:47:20.192038993Z'
closed: '2026-08-21T15:47:20.192038993Z'
parent: kno-01m0jbrv6wbe
tags:
- acceptance
- update
acceptance:
- title: knot update <id> --ac 2 --done flips the second frontmatter criterion; --ac 9 on a 4-item list exits 1
  done: true
- title: '--ac is repeatable: --ac 1 --ac 3 --done flips both in one write'
  done: true
- title: --remove-ac accepts an ordinal and exits 1 on any non-match instead of silently succeeding
  done: true
- title: 'A title that is all digits is still reachable (documented: ordinal wins; rename to disambiguate)'
  done: true
- title: knot show numbers each criterion; help for update and the skill describe the ordinal rule
  done: true
---

## Description

AC titles on real umbrellas run 150–250 characters; an agent flipping them must reproduce the title verbatim. Rule: an all-digits argument to --ac or --remove-ac is a 1-based ordinal into the frontmatter acceptance list; anything else is an exact title as today. Ordinals resolve against the list as it stands at that step of the existing apply order (add → flip → remove). --ac becomes repeatable; all flips share the one --done/--undone direction. No prefix matching.

Also fix the silent no-op: a non-matching --remove-ac (title or ordinal, including out-of-range) exits 1 with the same message shape as --ac. knot show renders the ordinal next to each criterion so agents read the number off the surface they flip it on.

## Notes

**2026-08-21T15:47:20.192038993Z**

An all-digits value on --ac or --remove-ac is now a 1-based ordinal; anything else stays an exact title, no prefix matching. Ordinals resolve against the list as it stands at that step of the existing add-flip-remove order. --ac is repeatable, all values sharing one --done/--undone direction, and knot show numbers each criterion so the number an agent reads is the number it passes back. A digit-titled criterion is reachable only by its own ordinal; rename to address it by name. Also fixed the silent no-op: a --remove-ac matching nothing now exits 1 rather than reporting success. Removal values resolve against one post-flip snapshot and are unioned, so --remove-ac 2 --remove-ac 3 removes both without index shift. acceptance/flip was orphaned by the change and removed. Verified in anger: this ticket's own five criteria were flipped by ordinal in a single write. git:f008df4
