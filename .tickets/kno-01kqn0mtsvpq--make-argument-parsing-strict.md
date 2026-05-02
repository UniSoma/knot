---
id: kno-01kqn0mtsvpq
title: Make argument parsing strict
status: open
type: task
priority: 2
mode: hitl
created: '2026-05-02T18:54:04.603784207Z'
updated: '2026-05-02T21:18:46.761399166Z'
tags:
- refine
---

## Notes

**2026-05-02T18:55:22.817137784Z**

For example, `--tag` passed to `knot create` does not errors

**2026-05-02T21:18:46.761399166Z**

`:create` was made strict in kno-01kqgqa7wnep (--afk/--hitl removal), since silent absorption was the load-bearing failure mode the removal needed to address. One fewer surface for this ticket to convert. Remaining unrestricted commands per `grep -L "restrict?" src/knot/help.clj`-ish: `:list`, `:show`, `:status`, `:start`, `:close`, `:reopen`, `:dep`, `:dep/tree`, `:undep`, `:link`, `:unlink`, `:add-note`, `:edit`, `:update`, `:check`, `:init` — verify and tighten as the ticket prescribes.
