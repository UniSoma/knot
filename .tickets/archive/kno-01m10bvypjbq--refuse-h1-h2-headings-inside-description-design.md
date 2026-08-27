---
id: kno-01m10bvypjbq
title: Refuse H1/H2 headings inside --description, --design, and add-note content
status: closed
type: bug
priority: 2
mode: afk
created: '2026-08-27T01:03:07.474341281Z'
updated: '2026-08-27T01:31:07.321820954Z'
closed: '2026-08-27T01:31:07.321820954Z'
tags:
- body
- sections
- write-guard
- body-write-surface
acceptance:
- title: 'create --description / --design with a line matching ^#{1,2}  exits 1 before any write, and the message names the heading and states that headings inside a section are ### or deeper, pointing at --body for whole sections'
  done: true
- title: update --description / --design refuse the same way; --body is unguarded
  done: true
- title: add-note refuses the same way for text arg, stdin, and editor content
  done: true
- title: Under --json the refusal uses the existing error envelope; tests pin text and JSON shapes and the unchanged section parser
  done: true
- title: The :desc of the affected flags/inputs states where a section ends; the knot skill carries the rule under Creating tickets and Notes and revisions; CONTEXT.md defines Body section
  done: true
links:
- kno-01m10bwqkraj
- kno-01m10bwqpmqx
- kno-01m10bwqsrk9
---

## Description

A ticket body is H2-delimited: every `## ` line starts a section, any name, and `###` and deeper nest inside it. `--description` and `--design` (on `create` and `update`) and `add-note` accept text containing its own `## ` lines without comment, so a description written as `## Problem` / `## Reporter` / `## Repro hint` is stored as four sibling sections. `show --json .sections.description` then returns only the prefix, and `update --description` with the same text replaces only up to the first foreign heading, leaving the ticket with the new copy followed by the old one. The reporting project has several tickets with two or three copies of their description; the agent that ran the update saw a success path and no warning.

Make the boundary rule unmissable at the moment it matters: `create --description`, `create --design`, `update --description`, `update --design`, and `add-note` (text arg, stdin, or editor) refuse content containing a line matching `^#{1,2} ` and exit 1 before any write. The message names the offending heading and states the rule: headings inside a section are `###` or deeper; to write whole sections use `--body`. `--body` stays unguarded — H2s are what it is for. No demotion, no warn-and-accept, no fence special-casing: the guard matches exactly what the parser splits on (plus H1, so the rule reads as one sentence: the title is the H1, knot's sections are the H2s).

The rule moves onto its context surfaces in the same change: the `:desc` of the five flags/inputs says where a section ends; the knot skill carries one sentence under Creating tickets and one under Notes and revisions; CONTEXT.md gains a `Body section` term. Nothing in prime — it is derivable.

## Design

Settled in a grilling session (2026-08-27). Rejected alternatives, for the record: treating only knot's own names (Description, Design, Notes) as section boundaries would make `--description` swallow legitimate sibling sections such as `## What to build` (this repo has ~90 foreign H2s authored as siblings) and would change the public `sections` JSON; demoting `##` to `###` silently rewrites the caller's prose; warning has no channel under `--json`. Fence awareness belongs in the parser first, if ever, and the guard follows.

The parser (`section-region`, `notes-region`, `body-sections`) is unchanged. The guard is one validation applied to every sectional write path, run before any file is touched; under `--json` it uses the existing error envelope and exit code convention.

## Notes

**2026-08-27T01:31:07.321820954Z**

One guard, `validate-no-section-headings!`, now refuses H1/H2 lines in every sectional write path — create/update --description and --design, and add-note across its text arg, stdin, and editor branches — throwing before any file is touched. The message names the offending heading and states the rule: a section ends at the next ## line, nest with ### or deeper, use update --body for whole sections. --body stays unguarded. create and update reach the existing json? -> invalid_argument fallback unchanged; add-note-handler, which previously re-threw everything but :ambiguous, gained two narrow :offending-heading branches so --json gets the standard error envelope and text gets die. The section parser is untouched. The rule landed on all three surfaces: the :desc of the four sectional flags, a :notes entry on add-note (its text input is a positional, and no positional in the registry carries a :desc), one sentence each under the skill's Creating tickets and Notes and revisions, and a Body section term in CONTEXT.md.
