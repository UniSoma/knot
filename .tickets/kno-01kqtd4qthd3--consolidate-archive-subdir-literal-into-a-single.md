---
id: kno-01kqtd4qthd3
title: Consolidate `"archive"` subdir literal into a single shared constant
status: open
type: chore
priority: 3
mode: afk
created: '2026-05-04T21:08:40.913022115Z'
updated: '2026-05-05T01:38:54.088449090Z'
tags:
- v0.3
- cleanup
links:
- kno-01kqsjaxb6dy
acceptance:
- title: A single `archive-subdir` def is the source of truth across the codebase
  done: false
- title: '`src/knot/store.clj`, `src/knot/check.clj`, and `src/knot/cli.clj` all reference that one constant; no other source file inlines `"archive"` for the subdir'
  done: false
- title: '`bb test` passes; `clj-kondo --lint src test` baseline unchanged (4 errors / 5 warnings)'
  done: false
- title: No behavior change observable from any CLI surface
  done: false
---

## Description

The `"archive"` subdirectory name is hardcoded across three source files:

- `src/knot/store.clj:16` — `(def ^:private archive-subdir "archive")`
- `src/knot/check.clj:245` — `(def ^:private archive-subdir "archive")` (private duplicate of the same def)
- `src/knot/cli.clj:1058` — inline `"archive"` in `info-data` building `:archive_path`
- `src/knot/cli.clj:1074` — inline `"archive"` in `count-md-files` invocation

`store.clj` and `check.clj` each define their own private `archive-subdir` constant for the same value, and `cli.clj` skips the constant entirely. Surfaced during code review of kno-01kqsjaxb6dy (commit 12911ed) — `info` introduced the third site without reusing either existing def.

Today this is harmless because the value is fixed everywhere. The risk is drift: if anyone ever parameterizes the archive-subdir name (e.g. `:archive-subdir` config key), the change has to touch four sites, and one will be missed.

Mechanical fix: promote a single non-private `archive-subdir` constant in the natural home (`src/knot/store.clj` is the storage layer, the canonical owner) and have `check.clj` and `cli.clj` reference it instead of redefining or inlining.
