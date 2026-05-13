---
id: kno-01kreh68sj1g
title: 'knot.el slice 8: cross-buffer refresh propagation + CLI version compat warning'
status: closed
type: feature
priority: 2
mode: afk
created: '2026-05-12T16:44:13.993865613Z'
updated: '2026-05-13T00:25:25.586810031Z'
closed: '2026-05-13T00:25:25.586810031Z'
parent: kno-01krebyvdr1w
tags:
- emacs
- knot-el
acceptance:
- title: After a successful mutating command, knot.el walks (window-list) and refreshes every visible knot.el buffer scoped to the same project
  done: true
- title: Buried (live but undisplayed) buffers are not auto-refreshed
  done: true
- title: g in any knot.el buffer manually re-fetches and re-renders
  done: true
- title: knot.el declares a minimum CLI version constant
  done: true
- title: On first knot-info-current call per project, knot.el compares data.project.knot_version against the declared minimum
  done: true
- title: If older, knot.el calls lwarn with a clear message; the mode still loads
  done: true
deps:
- kno-01kreh4yap1c
---

## Description

Polish slice that closes the refresh model and adds the CLI version-compat warning.

Every mutating command in earlier slices already auto-refreshes its own buffer; this slice extends that with a walk over the window list to refresh any other knot.el buffer for the same project currently displayed in a window — so a status change in show updates the visible list buffer alongside it. Buried (live but undisplayed) buffers are not auto-refreshed by design; g remains the manual escape hatch in any knot.el buffer.

On the first knot-info-current call in a project, compare `data.project.knot_version` against knot.el's declared minimum and `lwarn` (without refusing to load) if older. Refusing entirely would surprise users mid-task; surfacing in *Warnings* catches monorepo drift early.

No filewatch — external edits (from another agent or terminal session) surface only on next manual g (see docs/prd/knot-el.md 'Out of Scope').

See docs/prd/knot-el.md user stories 38-40 and 46, 'Refresh model', and 'Version compatibility'.

## Notes

**2026-05-13T00:25:25.586810031Z**

Slice 8 shipped at emacs/knot.el. knot--after-mutation walks (buffer-list) and refreshes every visible knot.el buffer (knot-list/info/show/deps) whose default-directory matches the originating buffer's project root, via get-buffer-window-list buf nil 0; buried buffers are intentionally untouched. Every mutating command — AC flip/add/remove, dep + link add/remove + remove-at-point, knot-update--commit, knot-start, knot-close, knot-capture-commit, the emacsclient sentinel, and knot-create--run — now calls knot--after-mutation in place of a self-only knot-show--refresh. knot-refresh (g) still does single-buffer refresh and now guards against non-knot buffers up front. CLI compat: knot-info-current fires knot-info--check-cli-version on each cache miss; it compares data.project.knot_version against knot-minimum-cli-version (0.3.0) via version< and lwarns once per project under the 'knot category when older, silent when the version field is missing or unparseable. Knot-info--version-warned persists across info-cache invalidations so the warning stays one-shot per Emacs session. Byte-compile + package-lint clean (the pre-existing 'stale-warning' on the Package-Requires Emacs 28.1 floor is unchanged).
