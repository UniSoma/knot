---
id: kno-01kqasv21t2x
title: Rephrase knot prime output to be directive, not descriptive
status: closed
type: feature
priority: 1
mode: afk
created: '2026-04-28T19:42:44.282541599Z'
updated: '2026-04-28T19:57:39.364009165Z'
closed: '2026-04-28T19:57:39.364009165Z'
tags:
- cli
- prime
- agent
---
## Description

The current prime output reads as environment description ("You are working in a Knot project. Tickets are markdown files..."), so agents file it under background trivia rather than direction. Rephrase as directives — explicit user-says/you-do mappings, plus negative-space guidance — so agents reach for knot instead of cat/grep/hand-editing. Origin: session on 2026-04-28 where the agent went to the filesystem to inspect a ticket despite prime being injected at session start.

## Design

Apply the following changes to the prime renderer (text and `--json` should track each other where applicable; the schema/commands cheatsheet is text-only):

- Open with a directive line, not a description. Replace the current "You are working in a Knot project. Tickets are markdown files..." preamble with something like "Use the `knot` CLI for all ticket reads and writes in this project — don't `cat`, `grep`, or hand-edit files under `.tickets/`. `knot` resolves partial IDs across live+archive and keeps frontmatter consistent."
- Add a "user-says → you-do" mapping section. This is the highest-leverage change: agents trigger on user phrasing, not on data structure. Cover at minimum:
  - "what's next?" / "what should I work on?" → `knot ready`
  - "let's tackle <id>" / "start working on..." → `knot show <id>`, then `knot start <id>`
  - "I'm done" / "shipped" / "let's close this" → `knot close <id> --summary "..."`
  - "note that..." / "FYI..." mid-task → `knot add-note <id> "..."`
  - "blocked on <other>" → `knot dep <current> <other>`
  - "what's blocking this?" → `knot dep tree <id>`
- Annotate the `In Progress` and `Ready` sections with one-line behavioral nudges. Under `In Progress`: "Resume here if the user picks up mid-stream." Under `Ready` (when In Progress is empty): "If asked 'what's next', recommend the top entry and confirm before `knot start`."
- Add explicit negative-space guidance: "Prefer `knot show <id>` over reading `.tickets/<id>--*.md` directly." and "Don't write to `.tickets/` by hand — `knot create`/`add-note`/`edit` keep frontmatter valid."
- Reorder: commands cheatsheet ahead of the schema reference. Frame schema as "if you must touch a file directly" — fallback, not invitation.
- Phrase commands pushily, like skill descriptions: "Use `knot ls` whenever the user references open work, even if they don't say 'ticket'."
- Bias toward table/bullet shape over prose so the directive content survives transcript compaction.
- Pick one canonical path per intent (don't list `knot edit` and "open the file in $EDITOR" as alternatives).

## Acceptance Criteria

- [ ] First non-blank line of `bb knot prime` output is a directive ("Use `knot` for...") rather than a description ("You are working in a Knot project...")
- [ ] Output contains a "user-says/you-do" mapping covering at least: what's-next, let's-tackle, I'm-done, note-that, blocked-on
- [ ] Output explicitly tells the agent NOT to `cat` or hand-edit files under `.tickets/`
- [ ] Commands cheatsheet appears before the schema reference in the text output
- [ ] `In Progress` and `Ready` sections each include a one-line behavioral nudge about when/how to act on them
- [ ] Existing `prime-end-to-end-test` integration tests still pass (section headings present, `--json` shape preserved, archive-only and empty-project paths unchanged)
- [ ] New integration test asserts the directive opening line and the presence of the user-phrase → command mapping
- [ ] `knot prime --json` output is unchanged in shape (the directive prose is text-only — JSON consumers shouldn't have a new key forced on them)
- [ ] Stretch: `knot prime` output length is not noticeably longer than today's output. The new content replaces description, doesn't pile onto it.

## Notes

**2026-04-28T19:57:39.364009165Z**

Rephrased prime output as directive: opens with 'Use the knot CLI...' instead of 'You are working in...'; added a user-says/you-do mapping (what's-next/tackle/I'm-done/note-that/blocked-on/what's-blocking), explicit don't-cat/don't-hand-edit guidance, In Progress and Ready section nudges, and split the Schema-and-commands block into a Commands cheatsheet (now ahead of) a Schema fallback reference. JSON shape unchanged; prime-end-to-end-test plus 5 new unit tests and 2 integration sub-tests all green (135 assertions total).
