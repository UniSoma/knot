---
id: kno-01kzxmbd81de
title: Correct the drifted knot claims in docs/agents and CHANGELOG
status: open
type: chore
priority: 2
mode: afk
created: '2026-08-13T13:17:57.377547683Z'
updated: '2026-08-13T13:17:57.377547683Z'
tags:
- docs
- cleanup
acceptance:
- title: docs/agents/issue-tracker.md creates a ticket with a positional title, not --title
  done: false
- title: docs/agents/issue-tracker.md lists --component and --acceptance-complete among the listing filters
  done: false
- title: docs/agents/triage-labels.md points at --add-tag / --remove-tag instead of claiming no single-tag command exists
  done: false
- title: The CHANGELOG 0.8.0 CPL description agrees with knot.query/coupling on closed neighbours
  done: false
- title: Every knot invocation shown in docs/agents/*.md executes as written
  done: false
---

## Description

## Description

Four drifts between the project's own agent docs and the shipped CLI, all
found while auditing the bundled knot skill against `knot help`:

1. `docs/agents/issue-tracker.md` shows
   `knot create --type <...> --title "..." --description "..."`. `create`
   takes a **positional** title and rejects the flag:
   `knot: Unknown option: :title`, exit 1. An agent following this doc fails
   on its first create.
2. The same file's listing-filter list stops at `--parent` / `--closure`,
   omitting `--component` (v0.9.0) and `--acceptance-complete`.
3. `docs/agents/triage-labels.md` says "there is no 'add one tag' command
   yet" and steers agents to `--tags <comma-list>`, which replaces the whole
   set. `--add-tag` / `--remove-tag` have shipped; the stale note actively
   causes tag loss when an agent adds one tag without reading the existing
   set first.
4. `CHANGELOG.md` `[0.8.0]` describes `CPL` as computed "with `:parent`
   excluded and closed neighbors still counted". `src/knot/query.clj`
   `coupling` drops them: "Closed (terminal) neighbors and broken refs (ids
   absent from the corpus) are dropped". The skill reference is correct; the
   CHANGELOG is not.

Item 4 is a historical entry — correcting it in place is the suggestion, but
leaving it and noting the correction under Unreleased is an acceptable
alternative if you'd rather not rewrite shipped release notes.

## Design

Mechanical. The check that found these is a per-command diff of documented
flags against `knot help <cmd>` output; re-running it after the edits is the
verification. Automating that check is the follow-up ticket that depends on
this one.
