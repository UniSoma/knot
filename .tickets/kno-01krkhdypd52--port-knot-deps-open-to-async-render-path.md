---
id: kno-01krkhdypd52
title: Port knot-deps--open to async render path
status: open
type: task
priority: 4
mode: afk
created: '2026-05-14T15:24:37.955613825Z'
updated: '2026-05-14T15:24:37.955613825Z'
tags:
- emacs
acceptance:
- title: knot-deps--open uses knot-cli-call-async
  done: false
- title: Loading indicator shown while deps tree is being computed
  done: false
- title: Deps tree renders identically to current behaviour after callback resolves
  done: false
- title: Superseded fetches are cancelled
  done: false
- title: bb test passes
  done: false
deps:
- kno-01krkhdr3akq
---

## Description

Apply the `knot-cli-call-async` pattern established in the parent ticket to `knot-deps--open`. The deps tree fetch is the second read-heavy path that benefits from non-blocking rendering.

Follow the API shape and design decisions recorded in the parent (callback signature, error arm, cancellation, loading indicator). No new API surface; this is a mechanical application of the proven pattern.

Source: `artifacts/investigate/20260514-1453-emacs-30-features-for-knot-el/REPORT.md` (Tier 4).
