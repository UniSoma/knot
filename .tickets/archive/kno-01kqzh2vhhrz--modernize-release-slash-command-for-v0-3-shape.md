---
id: kno-01kqzh2vhhrz
title: Modernize /release slash command for v0.3-shape cuts
status: closed
type: chore
priority: 3
mode: hitl
created: '2026-05-06T20:53:45.642991424Z'
updated: '2026-05-16T20:36:33.713979426Z'
closed: '2026-05-16T20:36:33.713979426Z'
tags:
- tooling
- release
- slash-command
- v0.5
acceptance:
- title: Step 4 rewritten to handle the [Unreleased] rename pattern
  done: true
- title: Coverage audit step (4b) added against `knot list --tag` filter
  done: true
- title: Release-notes prose drafting step added (single source for tag message + GH Release body)
  done: true
- title: Tag step uses annotated tag (`git tag -a -F`)
  done: true
- title: GitHub Release creation step added
  done: true
- title: Cleanup step (delete prose file, close coordination ticket) added
  done: true
- title: Pre-push bbin smoke verification step added
  done: true
links:
- kno-01kqgqfwk4h1
parent: kno-01krhwcy0zdy
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

Edit `.claude/commands/release.md` to replace its v0.2-shape procedure with the consolidated 12-step shape below. Also add a one-line entry to `.gitignore`: `release-notes-v*.txt`.

**One-time prerequisites (outside the per-cut procedure):**

- `.gitignore`: add `release-notes-v*.txt` so drafted release notes never accidentally land in a commit.
- Release tickets are tagged with the full `vX.Y.Z` form (e.g. `v0.5.0`), matching the git tag format — supports the audit step's `knot list --tag v<X.Y.Z>` lookup.

**Per-cut procedure (12 steps):**

1. **Gather context** — unchanged from current.
2. **Determine version** — unchanged.
3. **Update version** in `src/knot/version.clj` — unchanged.
4. **Coverage audit (before rename).** Cross-check two sources:
   - `knot list --tag v<X.Y.Z> --status closed` (intent / ticket discipline)
   - `git log --oneline <last-tag>..HEAD` (ground truth — every commit that landed)

   Reconcile both against the existing `[Unreleased]` bullet list. Backfill any missing entries *while the section is still `[Unreleased]`*. The git-log cross-check is what catches missed slices that lacked the release tag.
5. **Rename `[Unreleased]` → `[X.Y.Z] - YYYY-MM-DD`** and insert a fresh empty `[Unreleased]` skeleton above. If `[Unreleased]` was empty/missing, write the new entry below `## Versioning`.
6. **Draft release-notes prose** to `release-notes-v<X.Y.Z>.txt` at repo root (gitignored via the `release-notes-v*.txt` pattern). Single source for both the annotated tag message (Step 8) and the GitHub Release body (Step 11). Canonical structure (modeled on v0.3.0's shipped tag message):

   ~~~
   Release v<X.Y.Z> — <one-line theme>

   <1-3 sentence lead: breaking? headline change?>

   ## Highlights
   - **<phrase>.** <description>

   ## Breaking changes        # omit entire section for non-breaking releases
   1. **<title>.** <description>
      Migration: <command or one-liner>

   ## Upgrade path
   ```sh
   <bbin reinstall + per-release verification>
   ```

   ## Known follow-ups        # optional — omit when empty
   - <ticket id> — <one-liner>
   ~~~

7. **Commit:** `git add -A && git commit -m \"Release v<X.Y.Z>\"`.
8. **Annotated tag:** `git tag -a v<X.Y.Z> -F release-notes-v<X.Y.Z>.txt`.
9. **Pre-push smoke:** `bbin install . --as knot-rc`; `knot-rc --version | grep -q \"<X.Y.Z>\"` (grep-asserted); `knot-rc --help` (exit 0); `bbin uninstall knot-rc` unconditionally. Scope: confirm the binary starts and reports the right version — `info`/`check` are deliberately out of scope (would either need a knot project or risk false-positive failures against this repo's own tickets).
10. **Push:** `git push origin main --tags`.
11. **Create GitHub Release.** Prefer `gh release create v<X.Y.Z> -F release-notes-v<X.Y.Z>.txt --title \"v<X.Y.Z>\" --verify-tag` when `gh` is installed. Web-UI fallback when not: `github.com/<owner>/<repo>/releases/new`, paste the prose verbatim, tag-target `v<X.Y.Z>`. `--verify-tag` catches \"forgot to push tags\". No `--prerelease`; default to \"latest\".
12. **Cleanup:**
    - Verify slices closed: `knot list --tag v<X.Y.Z> --status open --json | jq -e '.data | length == 0'`. Abort with the open list on non-empty.
    - Close coordination ticket: `knot close <coord-id> --summary \"Cut v<X.Y.Z>. Release: <gh-url>.\"` (terse — rich content already lives in CHANGELOG + tag message + GH Release body).
    - Delete prose file: `rm release-notes-v<X.Y.Z>.txt`.

**Removed from the original Design:**

- Post-push tmpdir smoke. Offloaded to the dedicated release-tag smoke CI workflow tracked by `kno-01kqzh3jgwf0`. Keeping it in `/release` would duplicate the CI work without adding value.
- The `migrate-ac` smoke command. It is a v0.3-era one-shot migration; on a fresh tmpdir project it exits 0 trivially without exercising current code paths.

## Notes

**2026-05-13T23:55:59.202506785Z**

Sliding forward to v0.5. v0.4.0 was tagged 2026-05-13 (commit 932e2b5) as a lightweight tag, cut manually around this still-unmodernized /release command — same one-off as v0.3. CHANGELOG still carries the [Unreleased] rename pattern post-cut, so every AC in this ticket still applies as written.

**2026-05-16T20:21:47.298443127Z**

Design refreshed after a grilling session resolving all open branches. Key outcomes: (1) tag convention shifted to full vX.Y.Z for ticket tags; (2) coverage audit moved before rename and made dual-source (knot list --tag + git log); (3) prose template codified from v0.3.0's shipped tag message; CHANGELOG anchor link dropped as redundant; (4) post-push smoke removed (covered by kno-01kqzh3jgwf0); pre-push smoke narrowed to --version + --help with grep-asserted version; (5) GH Release uses gh CLI with web-UI fallback; --verify-tag catches forgotten pushes; (6) cleanup includes explicit slices-closed pre-check before relying on the open-children gate; (7) one-time .gitignore add for release-notes-v*.txt.

**2026-05-16T20:36:33.713979426Z**

Modernized .claude/commands/release.md from v0.2 to v0.5 shape: 12-step procedure with pre-rename dual-source coverage audit (knot list --tag + git log), [Unreleased] rename pattern, single-source release-notes prose (release-notes-vX.Y.Z.txt) feeding both annotated tag and GH Release body via gh CLI with web-UI fallback, pre-push grep-asserted version smoke, and cleanup with slices-closed pre-check. Added release-notes-v*.txt to .gitignore. Design + ACs walked through a grilling session before implementation.
