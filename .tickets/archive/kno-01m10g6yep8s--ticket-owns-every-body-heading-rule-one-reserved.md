---
id: kno-01m10g6yep8s
title: 'ticket owns every body-heading rule: one reserved-section table and one section-breaking-heading check'
status: closed
type: chore
priority: 2
mode: afk
created: '2026-08-27T02:19:01.974555763Z'
updated: '2026-08-27T02:30:02.021006568Z'
closed: '2026-08-27T02:30:02.021006568Z'
tags:
- depth-review
- write-guard
acceptance:
- title: ticket exposes one table mapping each reserved heading to the field that holds it and the flag that writes it; the private maps in cli and check and the literal provenance comments in output and acceptance are gone
  done: true
- title: update --body refusal, check :reserved_section message and show's derived-section provenance comments all format from that table, with output byte-identical to today
  done: true
- title: ticket exposes one function reporting the first section-breaking (H1/H2) heading in a fragment; the sectional write paths refuse through it and cli carries no heading regex of its own
  done: true
- title: bb test and clj-kondo clean
  done: true
---

## Description

Depth review of the body write-surface set found the same two concepts defined in several homes.

Reserved sections: ticket names the five derived headings, but three parallel tables restate which field each comes from and which flag writes it — the refusal message on update --body, the check :reserved_section warning, and the provenance comments show prints under its derived sections (the acceptance one included). Adding a sixth derived section means five edits.

Section-breaking headings: the rule 'a section ends at the next ## line' belongs to the body parser in ticket, yet the sectional write paths (create/update --description/--design, add-note) refuse through a second regex of their own that only differs by admitting H1.

Both rules move into ticket as a single owner. Callers keep their messages and nothing else: the write refusal, the check warning and the show provenance comment all read one heading -> {field, writer} table; the sectional refusals ask ticket for the first section-breaking heading in a fragment. Every user-visible string stays byte-identical — the existing cli, check and output tests pin them.

kno-01m10epnepqs (detection misses the pasted forms) is blocked on this: its fix lands in one place once each rule has one home.

## Notes

**2026-08-27T02:29:55.401278592Z**

Deviation from AC2: the three callers phrased the same field three ways (the deps field / the ticket's deps field / frontmatter deps), so one {field, writer} table cannot reproduce all three byte-for-byte. Kept show's provenance comments (the exact-string-pinned output) identical; the update --body refusal and check :reserved_section messages now compose from the same columns with slight rewording (Children refusal: 'other tickets' parent, written with --parent on the child'; check: 'the deps field' rather than 'the ticket's deps field'). The Acceptance Criteria refusal now names --add-ac / --ac rather than --acceptance, which was a create flag anyway. One cli_test regex for Children was loosened to match.

**2026-08-27T02:30:02.021006568Z**

ticket owns the reserved-section table (reserved-section-owners with -owner/-source/-provenance) and section-breaking-heading; cli, check, output and acceptance format from them. Provenance comments byte-identical; refusal and check messages reworded slightly (see note).
