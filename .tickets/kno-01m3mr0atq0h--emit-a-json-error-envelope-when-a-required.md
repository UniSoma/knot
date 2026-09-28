---
id: kno-01m3mr0atq0h
title: Emit a JSON error envelope when a required positional argument is missing
status: open
type: bug
priority: 3
mode: hitl
created: '2026-09-28T19:31:26.167245448Z'
updated: '2026-09-28T19:31:26.167245448Z'
tags:
- cli
- json
acceptance:
- title: Every command whose handler checks for a missing positional emits an error envelope under --json
  done: false
- title: Text-mode output and exit code for the missing-argument case are unchanged
  done: false
- title: The envelope's error code exists in references/json.md's error catalogue (reuse one or add one)
  done: false
---

## Description

With --json, a command missing its required positional argument (`knot show --json`, `knot update --json`, `knot delete --json`, and the document commands once PR #1 lands) prints plain text to stderr and exits 1 through `die` in main.clj. No envelope is written, so a caller parsing stdout gets nothing and never sees an `error.code`. Found while reviewing PR #1; the PR's document commands copy the existing pattern, so this is CLI-wide rather than something the PR introduced.
