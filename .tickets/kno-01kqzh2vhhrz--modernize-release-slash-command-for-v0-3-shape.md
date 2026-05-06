---
id: kno-01kqzh2vhhrz
title: Modernize /release slash command for v0.3-shape cuts
status: open
type: chore
priority: 3
mode: hitl
created: '2026-05-06T20:53:45.642991424Z'
updated: '2026-05-06T21:36:48.975272046Z'
tags:
- v0.4
- tooling
- release
- slash-command
acceptance:
- title: Step 4 rewritten to handle the [Unreleased] rename pattern
  done: false
- title: Coverage audit step (4b) added against `knot list --tag` filter
  done: false
- title: Release-notes prose drafting step added (single source for tag message + GH Release body)
  done: false
- title: Tag step uses annotated tag (`git tag -a -F`)
  done: false
- title: Pre-push and post-push bbin smoke verification steps added
  done: false
- title: GitHub Release creation step added
  done: false
- title: Cleanup step (delete prose file, close coordination ticket) added
  done: false
links:
- kno-01kqgqfwk4h1
- kno-01kqzkhpc244
---

## Description

The .claude/commands/release.md slash command was written for v0.2 and has gaps that surfaced during the v0.3 cut. v0.3 was cut manually; that one-off is fine, but the slash command should be brought in line so the next release can run it cleanly.

Concrete mismatches found at v0.3 cut time:

1. **Step 4 assumes 'add a new section at the top'** — but this CHANGELOG carries an `[Unreleased]` section that needs to be *renamed*, not stacked above. Running the command as-is would produce a malformed CHANGELOG.
2. **Step 6 creates a lightweight tag** (`git tag vX.Y.Z`) — v0.3 used an annotated tag (`git tag -a -F <prose>`) so `git show` carries the migration message and the same prose feeds the GitHub Release body.
3. **No coverage audit pass** — slice-by-slice CHANGELOG writing during the cycle missed two slices (`--add-ac`/`--remove-ac`, monotonic ULID). An audit step against `knot list --tag <release> --status closed` would have caught both.
4. **No release-notes prose drafting step** — the prose used for the annotated tag and the GitHub Release body has no place in the current procedure.
5. **No bbin install verification step** — pre-push dry-run + post-push e2e smoke is what makes the cut safe; today neither is named.

## Design

Edit `.claude/commands/release.md` to:

**Step 4 rewrite — '`[Unreleased]` rename pattern':**
> If `[Unreleased]` exists with content, rename it to `[X.Y.Z] - YYYY-MM-DD` and add a fresh empty `[Unreleased]` skeleton above. If `[Unreleased]` is empty or missing, write a new entry below the `## Versioning` block.

**New Step 4b — 'Coverage audit':**
> Run `knot list --tag v<X.Y.Z> --status closed` (where v<X.Y.Z> is the release tag). For each ticket, verify a corresponding bullet exists under the new section. Backfill any missed entries before continuing.

**New Step 5 — 'Draft release-notes prose':**
> Draft a release-notes prose block to a temp file (e.g. `release-notes-vX.Y.Z.txt` at repo root, gitignored or deleted post-cut). Structure: highlights, breaking changes with migration commands, upgrade path, link to CHANGELOG anchor. This single prose serves both the annotated tag message and the GitHub Release body.

**Step 6 (was Step 5) — Commit:** unchanged.

**Step 7 (was Step 6) — annotated tag:**
> `git tag -a vX.Y.Z -F release-notes-vX.Y.Z.txt` (annotated, prose from the temp file).

**New Step 8 — 'Pre-push smoke':**
> `bbin install . --as <name>-rc` from the working copy. Run `<name>-rc --version`, `--help`, `info`, `check` — verify the new version surfaces and golden path doesn't crash. Uninstall.

**Step 9 (was Step 7) — Push:** `git push origin main --tags`.

**New Step 10 — 'Post-push smoke':**
> Install from the public tag (`bbin install <repo-url>`) in a clean tmpdir; run `init` + `create` + `ls` + `--json ls` + `migrate-ac` + `check`. Uninstall.

**New Step 11 — 'Create GitHub Release':**
> At github.com/<owner>/<repo>/releases/new, paste the release-notes prose as the body. Tag-target: vX.Y.Z.

**New Step 12 — 'Cleanup':**
> Delete `release-notes-vX.Y.Z.txt`. Close the release-coordination ticket with a summary.
