---
id: kno-01m0jbrvfyxd
title: Address acceptance criteria by 1-based ordinal on --ac and --remove-ac; make --ac repeatable
status: open
type: feature
priority: 2
mode: afk
created: '2026-08-21T14:32:03.838787164Z'
updated: '2026-08-21T14:32:03.838787164Z'
parent: kno-01m0jbrv6wbe
tags:
- acceptance
- update
acceptance:
- title: knot update <id> --ac 2 --done flips the second frontmatter criterion; --ac 9 on a 4-item list exits 1
  done: false
- title: '--ac is repeatable: --ac 1 --ac 3 --done flips both in one write'
  done: false
- title: --remove-ac accepts an ordinal and exits 1 on any non-match instead of silently succeeding
  done: false
- title: 'A title that is all digits is still reachable (documented: ordinal wins; rename to disambiguate)'
  done: false
- title: knot show numbers each criterion; help for update and the skill describe the ordinal rule
  done: false
---

## Description

AC titles on real umbrellas run 150–250 characters; an agent flipping them must reproduce the title verbatim. Rule: an all-digits argument to --ac or --remove-ac is a 1-based ordinal into the frontmatter acceptance list; anything else is an exact title as today. Ordinals resolve against the list as it stands at that step of the existing apply order (add → flip → remove). --ac becomes repeatable; all flips share the one --done/--undone direction. No prefix matching.

Also fix the silent no-op: a non-matching --remove-ac (title or ordinal, including out-of-range) exits 1 with the same message shape as --ac. knot show renders the ordinal next to each criterion so agents read the number off the surface they flip it on.
